package foxlost.miracast.sink.p2p

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Labels the socket object, not the distinct filesystem inode created by bind(). */
internal object SupplicantSocketLabel {
    private data class CachedContext(val path: String, val inode: Long, val context: String)
    @Volatile private var cachedContext: CachedContext? = null
    private val whitespace = Regex("\\s+")
    private val level = "s[0-9]+(?::c[0-9]+(?:\\.c[0-9]+)?(?:,c[0-9]+(?:\\.c[0-9]+)?)*)?"
    private val contextSyntax = Regex("[A-Za-z0-9_]+:[A-Za-z0-9_]+:[A-Za-z0-9_]+:$level(?:-$level)?")

    internal fun validatedContext(bytes: ByteArray): String {
        // A single kernel terminator is permitted, not arbitrary trimming that
        // turns multiple labels or embedded control characters into a context.
        val context = bytes.toString(Charsets.UTF_8).removeSuffix("\u0000")
        check(context.length in 7..4095 && contextSyntax.matches(context)) {
            "Malformed or ambiguous socket security context"
        }
        return context
    }

    private external fun discoverNativeContext(inode: Long): ByteArray
    private external fun peerNativeContext(path: String): ByteArray

    internal fun discoverVerifiedContext(peer: () -> ByteArray, sockfs: () -> ByteArray): String {
        val peerBytes = try { peer() } catch (peerFailure: Exception) {
            try { return validatedContext(sockfs()) } catch (sockfsFailure: Exception) {
                sockfsFailure.addSuppressed(peerFailure)
                throw IOException("peer SID: ${peerFailure.message}; sockfs SID: ${sockfsFailure.message}",
                    sockfsFailure)
            }
        }
        // Never reinterpret malformed returned peer data as a different label.
        return validatedContext(peerBytes)
    }

    internal fun socketInode(table: String, path: String): Long {
        val matches = table.lineSequence().map { it.trim().split(whitespace, limit = 8) }
            .filter { it.size == 8 && it[4] == "0002" && it[7] == path }
            .mapNotNull { it[6].toLongOrNull() }.toList()
        check(matches.size == 1) { "No unique datagram endpoint for $path" }
        return matches.single()
    }

    private fun endpointInode(path: String) = socketInode(File("/proc/net/unix").readText(), path)

    private fun socketContext(fdPath: String, inode: Long): String {
        val expected = "socket:[$inode]"
        check(Os.readlink(fdPath) == expected) { "Socket descriptor changed" }
        val before = Os.stat(fdPath)
        check(OsConstants.S_ISSOCK(before.st_mode) && before.st_ino == inode) { "Not the expected sockfs inode" }
        // getxattr follows the proc magic link to the *sockfs* inode. Linux SELinux
        // socket_post_create initializes its SID and sk_security.sid identically.
        // The endpoint's on-disk sock_file label is deliberately never consulted.
        // Authority: Linux security/selinux/hooks.c selinux_socket_post_create,
        // selinux_inode_getsecurity and selinux_socket_unix_may_send (v6.1).
        val context = validatedContext(Os.getxattr(fdPath, "security.selinux"))
        val after = Os.stat(fdPath)
        check(Os.readlink(fdPath) == expected && after.st_ino == before.st_ino && after.st_dev == before.st_dev) {
            "Socket descriptor changed during label discovery"
        }
        return context
    }

    private fun discoverContext(path: String): Pair<Long, String> {
        val inode = endpointInode(path)
        cachedContext?.let {
            if (it.path == path && it.inode == inode) return inode to it.context
            cachedContext = null
        }
        RootIdentity.loadPrimitive()
        val context = try {
            discoverVerifiedContext({ peerNativeContext(path) }, { discoverNativeContext(inode) })
        } catch (e: Exception) {
            throw IOException("Supplicant SID discovery failed for $path: ${e.message}", e)
        } catch (e: LinkageError) {
            throw IOException("Native supplicant SID discovery unavailable for $path", e)
        }
        check(endpointInode(path) == inode) { "Supplicant endpoint changed during discovery: $path" }
        cachedContext = CachedContext(path, inode, context)
        return inode to context
    }

    internal fun <T> withCreationContext(
        context: String,
        read: () -> ByteArray,
        write: (ByteArray) -> Unit,
        create: () -> T
    ): T {
        validatedContext(context.toByteArray(Charsets.UTF_8))
        val previous = read()
        try {
            write((context + '\u0000').toByteArray(Charsets.UTF_8))
            check(validatedContext(read()) == context) { "sockcreate context not applied" }
            return create()
        } finally {
            // Preserve the exact prior value; an empty value denotes unset sockcreate.
            write(previous)
            check(read().contentEquals(previous)) { "sockcreate context not restored" }
        }
    }

    fun create(path: String): LocalSocket {
        val serverFile = Os.lstat(path)
        check(OsConstants.S_ISSOCK(serverFile.st_mode)) { "Server pathname is not a socket" }
        val (inode, context) = discoverContext(path)
        val handoff = Any()
        var socket: LocalSocket? = null
        var failure: Throwable? = null
        var completed = false
        var abandoned = false
        // Keep even a failed restoration confined to a thread that immediately exits.
        // Only this thread owns the socket until the restored result is handed off.
        val creator = thread(name = "Supplicant-socket-create", isDaemon = true) {
            var localSocket: LocalSocket? = null
            var localFailure: Throwable? = null
            try {
                val attr = File("/proc/thread-self/attr/sockcreate").takeIf { it.exists() }
                    ?: File("/proc/self/task/${Os.gettid()}/attr/sockcreate")
                withCreationContext(context, attr::readBytes, { bytes ->
                    val fd = Os.open(attr.path, OsConstants.O_WRONLY or OsConstants.O_CLOEXEC, 0)
                    try {
                        // A NUL also clears the kernel attribute. Use a nonempty buffer
                        // rather than depending on Java's zero-length write behavior.
                        val value = if (bytes.isEmpty()) byteArrayOf(0) else bytes
                        check(Os.write(fd, value, 0, value.size) == value.size) { "Short sockcreate write" }
                    } finally { Os.close(fd) }
                }) {
                    val created = LocalSocket(LocalSocket.SOCKET_DGRAM)
                    localSocket = created
                    created.inputStream // Force lazy socket(2) inside the sockcreate scope.
                    // The daemon's listening sock_file label is not a client reply
                    // label. An abstract reply has no filesystem inode/access gate;
                    // its sockfs SID still uses the verified daemon socket context.
                    val name = "miracast_${android.os.Process.myPid()}_${java.util.UUID.randomUUID()}"
                    created.bind(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                    val unchanged = Os.lstat(path)
                    check(unchanged.st_ino == serverFile.st_ino && unchanged.st_dev == serverFile.st_dev) {
                        "Server pathname changed during bind"
                    }
                }
            } catch (e: Throwable) { localFailure = e }
            val discard = synchronized(handoff) {
                if (abandoned) true else {
                    socket = localSocket
                    failure = localFailure
                    completed = true
                    false
                }
            }
            // No caller can use an abandoned socket. Creation and restoration are
            // finished before close; never race close against LocalSocket lazy create.
            if (discard && localSocket?.fileDescriptor != null) {
                try { localSocket?.close() } catch (_: Exception) { }
            }
        }
        try {
            creator.join(750)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val created = synchronized(handoff) {
            if (!completed) {
                abandoned = true
                throw IOException("Timed out or interrupted waiting for scoped supplicant socket creation")
            }
            socket
        }
        try {
            if (Thread.currentThread().isInterrupted) {
                throw java.io.InterruptedIOException("Interrupted during scoped supplicant socket creation")
            }
            failure?.let { throw IOException("Scoped supplicant sockcreate failed: ${it.message}", it) }
            checkNotNull(created) { "Socket was not created" }
            val ownInode = Os.fstat(created.fileDescriptor).st_ino
            val ownFd = File("/proc/self/fd").list().orEmpty().firstOrNull {
                try { Os.readlink("/proc/self/fd/$it") == "socket:[$ownInode]" } catch (_: Exception) { false }
            } ?: error("Cannot locate created socket descriptor")
            check(socketContext("/proc/self/fd/$ownFd", ownInode) == context) { "Created socket SID mismatch" }
            check(endpointInode(path) == inode) { "Supplicant endpoint changed before connect" }
            created.soTimeout = 1200
            created.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            val connectedPath = Os.lstat(path)
            check(connectedPath.st_ino == serverFile.st_ino && connectedPath.st_dev == serverFile.st_dev &&
                endpointInode(path) == inode) { "Supplicant endpoint changed during connect: $path" }
            return created
        } catch (e: Throwable) {
            // close() on a never-created LocalSocket can itself lazily create a socket.
            if (created?.fileDescriptor != null) try { created.close() } catch (_: Exception) { }
            throw e
        }
    }
}
