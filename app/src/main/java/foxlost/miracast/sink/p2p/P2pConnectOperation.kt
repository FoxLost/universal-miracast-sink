package foxlost.miracast.sink.p2p

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** An app-channel CONNECT and its one cleanup result, shared by overlapping stop/failure paths. */
internal class P2pConnectOperation(val generation: Long, val address: String) {
    private val owner = RootPendingConnect().apply { dispatched(generation, address) }
    private val rejected = AtomicBoolean(false)
    private val settling = AtomicBoolean(false)
    private val completion = CompletableFuture<Boolean>()

    fun rejected() { rejected.set(true) }
    fun canReplace(): Boolean = (rejected.get() && !settling.get()) || completion.getNow(false)
    fun wasRejected(): Boolean = rejected.get()

    /** Runs off the UI thread. inspect and cancel must refer to this operation's original owners. */
    fun settle(inspect: (String) -> RootPendingConnect.State, cancel: () -> Unit): Boolean {
        if (settling.compareAndSet(false, true)) {
            val clean = try {
                owner.close(generation, {
                    if (rejected.get()) RootPendingConnect.State.COMPLETED else inspect(it)
                }, {
                    // A late explicit CONNECT rejection is completion proof, not a cancel ACK.
                    if (!rejected.get()) cancel()
                })
                true
            } catch (_: Exception) { false }
            completion.complete(clean)
        }
        return try { completion.get(45, TimeUnit.SECONDS) } catch (_: Exception) { false }
    }
}
