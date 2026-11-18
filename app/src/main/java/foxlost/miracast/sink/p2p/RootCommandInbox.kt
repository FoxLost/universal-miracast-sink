package foxlost.miracast.sink.p2p

import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** Observe owner death even while the command thread is inside a framework call. */
internal class RootCommandInbox(input: InputStream) {
    private val commands = ArrayBlockingQueue<String>(1)
    @Volatile private var ended = false

    init {
        thread(name = "RootFramework-stdin", isDaemon = true) {
            try {
                input.bufferedReader().use { reader ->
                    while (true) {
                        val command = reader.readLine() ?: break
                        // The private protocol permits one outstanding command, never a backlog.
                        if (!commands.offer(command)) break
                    }
                }
            } catch (_: java.io.IOException) {
                // A broken owner pipe has the same cancellation semantics as EOF.
            } finally {
                ended = true
                commands.offer("") // Wake an idle command thread; pending commands are discarded.
            }
        }
    }

    fun next(): String? {
        if (ended) return null
        val command = commands.take()
        return if (ended) null else command
    }

    fun checkActive() {
        if (ended) throw CancellationException("Root session cancelled by owner")
    }
}
