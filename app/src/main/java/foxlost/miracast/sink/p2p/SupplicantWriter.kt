package foxlost.miracast.sink.p2p

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

/**
 * wpa_supplicant control-interface writer, run as root via:
 *   CLASSPATH=<apk> app_process / foxlost.miracast.sink.p2p.SupplicantWriter <sock> <cmd...>
 *
 * Uses the public android.net.LocalSocket (SOCK_DGRAM) API. The supplicant
 * control socket is a Unix datagram socket; the client must bind() a unique
 * local path before connect() so the server can address replies back.
 *
 * Each command is written as a newline-terminated control message. An optional
 * "IFNAME=<iface> " prefix targets a specific interface on a shared socket.
 */
object SupplicantWriter {
    private const val TAG = "SupplicantWriter"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: SupplicantWriter <socket-path> <cmd1> [cmd2...]")
            return
        }
        val socketPath = args[0]
        val cmds = args.drop(1)
        try {
            val socket = LocalSocket(LocalSocket.SOCKET_DGRAM)

            // Unique local endpoint so the server can reply to us.
            val clientPath = "${socketPath}_sw${android.os.Process.myPid()}"
            socket.bind(LocalSocketAddress(clientPath, LocalSocketAddress.Namespace.FILESYSTEM))
            // The supplicant runs as uid `wifi`; our endpoint is created as
            // root with mode 0700, so the reply sendto() fails with EACCES
            // ("ctrl_iface sendto failed: 13 - Permission denied"). Open it up.
            try { android.system.Os.chmod(clientPath, 0b111111111) } catch (_: Exception) {}

            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))

            val out: OutputStream = socket.outputStream
            for (cmd in cmds) {
                out.write("$cmd\n".toByteArray())
                out.flush()
                println("SENT: $cmd")
                Thread.sleep(120)
            }

            // Best-effort drain of any replies (OK/FAIL/event lines).
            try {
                socket.soTimeout = 400
                val inp: InputStream = socket.inputStream
                val buf = ByteArray(2048)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    println("RECV: ${String(buf, 0, n).trim()}")
                }
            } catch (_: Exception) {
                // read timeout just means no more data
            }

            socket.close()
            println("OK")
        } catch (e: Exception) {
            println("ERR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
