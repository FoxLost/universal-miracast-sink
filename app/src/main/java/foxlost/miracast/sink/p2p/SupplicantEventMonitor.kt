package foxlost.miracast.sink.p2p

import android.net.LocalSocket
import android.net.LocalSocketAddress

/**
 * wpa_supplicant control-interface EVENT MONITOR, run as root via:
 *   CLASSPATH=<apk> app_process / foxlost.miracast.sink.p2p.SupplicantEventMonitor <sock>
 *
 * Attaches (ATTACH) to the supplicant control socket and streams every
 * unsolicited event line (P2P-GO-NEG-REQUEST, P2P-PROV-DISC-*, ...) to stdout,
 * one per line, flushed. The hosting app spawns this via `su` and parses the
 * stream to drive the headless auto-accept flow.
 *
 * Why this exists: the framework answers an incoming GO Negotiation Request
 * with Status 1 ("not ready") until someone calls p2pConnect(). Stock Android
 * does that from the Accept button of a system dialog (notifyInvitationReceived
 * shows an AlertDialog — there is NO broadcast to observe). A headless sink
 * has no user, so we watch the supplicant directly and arm it ourselves.
 *
 * NOTE: the client socket path must be world-writable — the supplicant runs
 * as the `wifi` user and detaches monitors whose event delivery fails with
 * EACCES (observed on-device: "ctrl_iface sendto failed: 13").
 */
object SupplicantEventMonitor {

    @JvmStatic
    fun main(args: Array<String>) {
        val socketPath = args.getOrNull(0) ?: run {
            System.err.println("Usage: SupplicantEventMonitor <socket-path>")
            return
        }
        try {
            val socket = LocalSocket(LocalSocket.SOCKET_DGRAM)
            val clientPath = "${socketPath}_mon${android.os.Process.myPid()}"
            socket.bind(LocalSocketAddress(clientPath, LocalSocketAddress.Namespace.FILESYSTEM))
            // Supplicant (uid wifi) must be able to sendto() our socket or it
            // will detach us. We run as root, so chmod the endpoint open.
            try { android.system.Os.chmod(clientPath, 0b111111111) } catch (_: Exception) {}

            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.outputStream.write("ATTACH\n".toByteArray())
            socket.outputStream.flush()

            val inp = socket.inputStream
            val buf = ByteArray(4096)
            val pending = StringBuilder()
            fun emitCompleteLines() {
                while (true) {
                    val newline = pending.indexOf("\n")
                    if (newline < 0) return
                    val line = pending.substring(0, newline).trim()
                    pending.delete(0, newline + 1)
                    if (line.isNotEmpty()) {
                        println(line)
                        System.out.flush()
                    }
                }
            }
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                emitCompleteLines()
            }
            // Datagram/socket implementations normally terminate records with
            // LF, but do not silently lose a final partial event on EOF.
            val last = pending.toString().trim()
            if (last.isNotEmpty()) {
                println(last)
                System.out.flush()
            }
        } catch (e: Exception) {
            System.err.println("MONITOR-ERR: ${e.message}")
        }
    }
}
