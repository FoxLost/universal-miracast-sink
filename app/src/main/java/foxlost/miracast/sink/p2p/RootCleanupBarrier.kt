package foxlost.miracast.sink.p2p

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class RootCleanupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Each owner reserves its place before launching; only its own completion can release it. */
internal class RootCleanupBarrier {
    private var tail = CompletableFuture.completedFuture(true)

    fun awaitClean(timeoutMs: Long): Boolean {
        val pending = synchronized(this) { tail }
        return try { pending.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        catch (_: Exception) { false }
    }

    @Synchronized
    fun reserve(): Owner {
        val completion = CompletableFuture<Boolean>()
        val previous = tail
        tail = previous.thenCombine(completion) { prior, current -> prior && current }
        return Owner(previous, completion)
    }

    class Owner internal constructor(
        private val previous: CompletableFuture<Boolean>,
        private val completion: CompletableFuture<Boolean>,
    ) {
        fun awaitPrevious() {
            val clean = try {
                previous.get(75, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw RootCleanupException("Previous root session restoration is still pending; wait for teardown and retain root cleanup diagnostics", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RootCleanupException("Waiting for previous root session restoration was interrupted", e)
            }
            if (!clean) throw RootCleanupException("Previous root session restoration failed; root permission was not checked. Restore Wi-Fi state (reboot if necessary) before restarting the app, and retain root cleanup diagnostics")
        }

        fun complete(clean: Boolean) {
            completion.complete(clean)
        }
    }
}
