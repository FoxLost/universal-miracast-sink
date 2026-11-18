package foxlost.miracast.sink.p2p

import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One stdout reader owns replies and restoration proof, including after an RPC is cancelled. */
internal class RootFrameworkChannel(input: InputStream, private val idleTimeoutMs: Long = 12_000) {
    private val replies = ArrayBlockingQueue<String>(4)
    private val idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)
    @Volatile private var startupProgressAt = Long.MIN_VALUE
    val cleanup = CompletableFuture<Boolean>()

    init {
        require(idleTimeoutMs > 0)
        thread(name = "RootFramework-stdout", isDaemon = true) {
            var completedSteps = -1
            try {
                input.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line == "CLEANUP OK" -> cleanup.complete(true)
                            line == "CLEANUP ERR" -> cleanup.complete(false)
                            line.startsWith("PROGRESS ") -> {
                                val fields = line.split(' ')
                                check(fields.size == 4 && fields[1] == "START") {
                                    "Invalid framework progress"
                                }
                                val completed = fields[2].toInt()
                                val total = fields[3].toInt()
                                check(completed in 0..total) { "Invalid framework progress count" }
                                // Only completed preparation steps can extend a START idle deadline.
                                if (completed > completedSteps) {
                                    completedSteps = completed
                                    startupProgressAt = System.nanoTime()
                                }
                            }
                            else -> check(replies.offer(line)) { "Unexpected framework reply backlog" }
                        }
                    }
                }
            } catch (e: Exception) {
                replies.offer("ERR Framework reply pipe failed: ${e.message}")
            } finally {
                // Exit status of a su wrapper is not proof that its app_process child restored state.
                cleanup.complete(false)
                replies.offer("EOF")
            }
        }
    }

    fun readReply(startup: Boolean = false): String {
        val began = System.nanoTime()
        while (true) {
            val lastActivity = if (startup) maxOf(began, startupProgressAt) else began
            val remaining = idleNanos - (System.nanoTime() - lastActivity)
            if (remaining <= 0) throw SocketTimeoutException("Root framework made no progress for ${idleTimeoutMs}ms")
            val reply = replies.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (reply == "EOF") {
                // Keep EOF visible to another waiter after an interrupted RPC.
                replies.offer("EOF")
                throw EOFException("Root framework reply pipe closed")
            }
            return reply
        }
    }
}
