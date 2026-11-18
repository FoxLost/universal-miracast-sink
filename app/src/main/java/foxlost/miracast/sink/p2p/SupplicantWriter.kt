package foxlost.miracast.sink.p2p

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class SupplicantTransportUnavailable(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/** Internal root-only socket transport. Paths are discovered here, never accepted over IPC. */
internal object SupplicantWriter {
    private val directories = listOf("/data/vendor/wifi/wpa/sockets", "/data/misc/wifi/sockets",
        "/data/vendor/wifi/wpa", "/data/misc/wifi/wpa_supplicant")
    private var preferred: Pair<String, String>? = null
    private var failedSnapshot: String? = null
    private var failedReason: String? = null

    fun candidates(): List<Pair<String, String>> {
        check(Os.getuid() == 0) { "Root socket access required" }
        val interfaces = File("/sys/class/net").list().orEmpty()
            .filter { SupplicantControlSession.validInterface(it) && (it.startsWith("p2p") || it == "wlan0") }
        val paths = directories.flatMap { dir ->
            (interfaces + listOf("p2p0", "wlan0", "global")).distinct().map { "$dir/$it" }
        } + listOf("/dev/socket/wpa_wlan0", "/dev/socket/wpa_p2p0", "/dev/socket/wpa_global")
        // @android:wpa_wlan0 means the init-inherited RESERVED /dev/socket fd,
        // not an abstract address. Only actual filesystem socket inodes qualify.
        return paths.distinct().filter { path ->
            try { OsConstants.S_ISSOCK(Os.lstat(path).st_mode) } catch (_: Exception) { false }
        }.map { path -> path.substringAfterLast('/') to path }
    }

    private fun snapshot(sockets: List<Pair<String, String>>): String = sockets.joinToString(";") { (_, path) ->
        try { val stat = Os.lstat(path); "$path:${stat.st_dev}:${stat.st_ino}:${stat.st_ctime}" }
        catch (_: Exception) { "$path:gone" }
    } + File("/sys/class/net").list().orEmpty().sorted().joinToString(",") { iface ->
        val state = try { File("/sys/class/net/$iface/operstate").readText().trim() } catch (_: Exception) { "unknown" }
        "$iface:$state"
    }

    class Connection(val iface: String, val path: String) : AutoCloseable {
        private var endpoint: android.net.LocalSocket? = null
        val socket: android.net.LocalSocket get() = checkNotNull(endpoint)
        private val receiveBuffer = ByteArray(65536)
        private val closed = AtomicBoolean(false)
        private var replyDeadline = 0L
        private val pendingEvents = ArrayDeque<String>()
        private val control = SupplicantControlSession(::send, ::receive) { pendingEvents.addLast(it) }

        init {
            try {
                endpoint = SupplicantSocketLabel.create(path)
                // connect pins this datagram socket to the verified daemon: Linux
                // rejects sends from any other socket, even if its abstract name
                // is discovered. Discard anything queued before connect before
                // accepting PONG, peer data, or command acknowledgements.
                discardQueuedDatagrams()
                transaction { control.verify() }
            } catch (e: Exception) {
                try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                throw e
            }
        }

        private fun discardQueuedDatagrams() {
            val timeout = socket.soTimeout
            try {
                // SO_RCVTIMEO is public on every supported Android version.
                // This short timeout applies only before the first command.
                socket.soTimeout = 1
                repeat(256) {
                    try {
                        Os.recvfrom(socket.fileDescriptor, receiveBuffer, 0, receiveBuffer.size, OsConstants.MSG_TRUNC, null)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) return
                        if (e.errno != OsConstants.EINTR) throw e
                    }
                }
                error("Supplicant reply queue did not drain")
            } finally {
                socket.soTimeout = timeout
            }
        }

        private fun send(command: String) {
            val bytes = command.toByteArray(Charsets.UTF_8)
            check(Os.write(socket.fileDescriptor, bytes, 0, bytes.size) == bytes.size) { "Short supplicant datagram" }
        }

        fun receive(): String {
            while (true) {
                if (replyDeadline != 0L) {
                    val remaining = replyDeadline - System.nanoTime()
                    check(remaining > 0) { "Supplicant reply deadline expired" }
                    socket.soTimeout = ((remaining + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
                }
                val count = try {
                    Os.recvfrom(socket.fileDescriptor, receiveBuffer, 0, receiveBuffer.size, OsConstants.MSG_TRUNC, null)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                check(count > 0) { "Supplicant EOF or empty datagram" }
                check(count <= receiveBuffer.size) { "Truncated supplicant datagram" }
                return String(receiveBuffer, 0, count, Charsets.UTF_8).trimEnd('\r', '\n')
            }
        }

        private fun <T> transaction(action: () -> T): T {
            replyDeadline = System.nanoTime() + 1_200_000_000L
            try { return action() } finally { replyDeadline = 0L }
        }
        fun request(command: String): String = transaction { control.request(command) }
        fun mutate(command: String): Boolean = transaction { control.mutate(command) }
        fun attach() = transaction { control.attach() }
        fun nextEvent(): String = if (pendingEvents.isEmpty()) receive() else pendingEvents.removeFirst()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            endpoint?.close()
        }
    }

    private fun <T> onVerifiedRoute(cacheFailure: Boolean = true, action: (Connection) -> T?): T? {
        val discovered = candidates()
        val snapshot = snapshot(discovered)
        if (cacheFailure && snapshot == failedSnapshot) {
            throw SupplicantTransportUnavailable(failedReason ?: "Supplicant transport unavailable")
        }
        val sockets = preferred?.let { first -> listOf(first).filter { it in discovered } + discovered.filter { it != first } }
            ?: discovered
        var verifiedTransport = false
        var transportFailure: Exception? = null
        // A final candidate can still spend 1.2s probing, 2s scanning, 0.75s
        // creating and 2.4s verifying/requesting. Reserve that within the
        // client's 12s watchdog; never start another route near its deadline.
        val deadline = System.nanoTime() + 5_000_000_000L
        for ((iface, path) in sockets) {
            if (Thread.currentThread().isInterrupted) {
                throw java.io.InterruptedIOException("Interrupted while selecting supplicant transport")
            }
            if (System.nanoTime() >= deadline) break
            try {
                Connection(iface, path).use { connection ->
                    verifiedTransport = true
                    val result = action(connection)
                    if (result != null) {
                        preferred = iface to path
                        return result
                    }
                }
            } catch (e: SupplicantOutcomeUnknown) {
                throw e // Never replay a possibly delivered mutation, even on another route.
            } catch (e: java.io.InterruptedIOException) {
                if (Thread.currentThread().isInterrupted) throw e
                transportFailure = e
            } catch (e: Exception) {
                if (transportFailure == null) transportFailure = e else transportFailure.addSuppressed(e)
            }
        }
        if (!verifiedTransport) {
            val reason = "No verified supplicant transport: ${transportFailure?.message ?: "no socket candidates"}"
            // Readonly peer readiness can change during formation; its bounded
            // caller may retry without latching the first unavailable transport.
            if (cacheFailure) { failedSnapshot = snapshot; failedReason = reason }
            throw SupplicantTransportUnavailable(reason, transportFailure)
        }
        return null
    }

    fun execute(command: String): Boolean {
        require(RootBridgeProtocol.validSupplicant(command))
        // Cleanup must retry even when an earlier failed discovery was cached.
        return onVerifiedRoute(cacheFailure = !isNeutral(command)) { if (it.mutate(command)) true else null } ?: false
    }

    internal fun isNeutral(command: String): Boolean =
        command == "P2P_SET disallow_freq " || command == "P2P_EXT_LISTEN 0 0" ||
            command == "WFD_SUBELEM_SET 0 " || command == "WFD_SUBELEM_SET 7 "

    fun readPeerInterfaceAddress(interfaceName: String, peerMac: String): String? {
        RootBridgeProtocol.validatePeerLookup(interfaceName, peerMac)
        // P2P_PEER is a readonly device lookup on the verified management context.
        // The caller binds its result to the framework-selected group/interface;
        // a group interface need not expose a separate control endpoint.
        return onVerifiedRoute(cacheFailure = false) { connection ->
            RootBridgeProtocol.peerInterfaceAddress(peerMac, connection.request("P2P_PEER $peerMac"))
        }
    }
}

object RootSocketBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        var session: RootSocketSession? = null
        var status = 0
        try {
            check(args.isEmpty() && Os.getuid() == 0) { "Root launch required" }
            val input = System.`in`.bufferedReader()
            check(input.readLine() == "SESSION") { "Missing session handshake" }
            val owner = RootSocketSession(SupplicantWriter::execute, { File("/proc/net/arp").readText() },
                SupplicantWriter::readPeerInterfaceAddress)
            session = owner
            println("OK")
            System.out.flush()
            input.forEachLine { line ->
                val reply = try { owner.request(line) } catch (e: Exception) {
                    "ERR ${e.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(1024)}"
                }
                println(reply)
                System.out.flush()
            }
        } catch (e: Exception) {
            System.err.println("Root socket owner failed: ${e.message}")
            status = 1
        } finally {
            try { session?.close() } catch (e: Exception) {
                System.err.println("Root socket cleanup failed: ${e.message}")
                status = 1
            }
            kotlin.system.exitProcess(status)
        }
    }
}
