package foxlost.miracast.sink.p2p

import android.system.Os
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Root-discovered fixed monitor. Parent pipe EOF closes the socket and exits. */
object SupplicantEventMonitor {
    @JvmStatic
    fun main(args: Array<String>) {
        val ownerEnded = AtomicBoolean(false)
        try {
            check(args.isEmpty() && Os.getuid() == 0) { "Root launch required" }
            var attached: SupplicantWriter.Connection? = null
            var transportFailure: Exception? = null
            val deadline = System.nanoTime() + 8_000_000_000L
            for ((iface, path) in SupplicantWriter.candidates()) {
                if (System.nanoTime() >= deadline) break
                val connection = try { SupplicantWriter.Connection(iface, path) } catch (e: Exception) {
                    transportFailure = e
                    continue
                }
                try {
                    connection.attach()
                    attached = connection
                    break
                } catch (e: Exception) { transportFailure = e; connection.close() }
            }
            val connection = attached ?: throw IllegalStateException(
                "No verified supplicant monitor transport: ${transportFailure?.message ?: "no socket candidates"}",
                transportFailure
            )
            connection.use {
                thread(name = "Monitor-owner", isDaemon = true) {
                    try {
                        while (System.`in`.read() != -1) { }
                        ownerEnded.set(true)
                    } catch (e: IOException) {
                        System.err.println("MONITOR_OWNER_ERR: ${e.message}")
                    } finally {
                        try { connection.close() } catch (e: IOException) {
                            System.err.println("MONITOR_CLOSE_ERR: ${e.message}")
                        }
                    }
                }
                // A finite idle receive also observes EOF if close(2) cannot wake
                // an in-flight datagram syscall on this vendor kernel.
                connection.socket.soTimeout = 1000
                println("MONITOR_READY")
                System.out.flush()
                while (!ownerEnded.get()) {
                    val event = try { connection.nextEvent() } catch (e: android.system.ErrnoException) {
                        if (e.errno == android.system.OsConstants.EAGAIN) continue
                        throw e
                    }
                    if (event.startsWith("<") || event.startsWith("IFNAME=")) {
                        println(event)
                        System.out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            if (!ownerEnded.get()) {
                System.err.println("MONITOR_ERR: ${e.message}")
                kotlin.system.exitProcess(1)
            }
        }
    }
}
