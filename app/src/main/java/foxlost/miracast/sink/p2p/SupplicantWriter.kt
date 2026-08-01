package foxlost.miracast.sink.p2p

import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

object SupplicantWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: SupplicantWriter <socket-name> <cmd1> [cmd2...]")
            return
        }
        val socketName = args[0]
        val cmds = args.drop(1)
        try {
            // Create SOCK_DGRAM LocalSocket via LocalSocketImpl (wpa_supplicant uses DGRAM)
            val implClass = Class.forName("android.net.LocalSocketImpl")
            val impl = implClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            implClass.getDeclaredMethod("create", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(impl, 2) // 2 = SOCK_DGRAM

            val address = if (socketName.startsWith("@")) {
                LocalSocketAddress(socketName.substring(1), LocalSocketAddress.Namespace.ABSTRACT)
            } else {
                LocalSocketAddress(socketName, LocalSocketAddress.Namespace.FILESYSTEM)
            }
            implClass.getDeclaredMethod("connect", LocalSocketAddress::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(impl, address, 2000)

            val getOut = implClass.getDeclaredMethod("getOutputStream").apply { isAccessible = true }
            val out = getOut.invoke(impl) as OutputStream
            for (cmd in cmds) {
                out.write("$cmd\n".toByteArray())
                out.flush()
                Thread.sleep(100)
            }

            // Read response
            try {
                val getIn = implClass.getDeclaredMethod("getInputStream").apply { isAccessible = true }
                val inp = getIn.invoke(impl) as InputStream
                val resp = ByteArray(256)
                val n = inp.read(resp)
                if (n > 0) println("RESP: ${String(resp, 0, n).trim()}")
            } catch (e: Exception) {
                println("RESP: <timeout/closed>")
            }

            implClass.getDeclaredMethod("close").apply { isAccessible = true }.invoke(impl)
            println("OK")
        } catch (e: Exception) {
            println("ERR: ${e.message?.take(80)}")
        }
    }
}
