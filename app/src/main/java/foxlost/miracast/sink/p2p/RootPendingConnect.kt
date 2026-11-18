package foxlost.miracast.sink.p2p

/** One CONNECT dispatched by one framework helper/channel, including a lost Binder reply. */
internal class RootPendingConnect {
    enum class State { PENDING, COMPLETED, UNRELATED, UNKNOWN }
    private data class Operation(val generation: Long, val address: String)
    private var operation: Operation? = null

    fun dispatched(generation: Long, address: String) {
        check(operation == null) { "Previous CONNECT still owned" }
        operation = Operation(generation, address)
    }

    /** Only a synchronous, explicit CONNECT rejection releases ownership without inspection. */
    fun rejected(generation: Long, address: String) {
        check(operation == Operation(generation, address)) { "Stale CONNECT rejection" }
        operation = null
    }

    fun close(generation: Long, inspect: (String) -> State, cancel: () -> Unit) {
        val owned = operation ?: return
        check(owned.generation == generation) { "Stale CONNECT cleanup" }
        when (inspect(owned.address)) {
            State.COMPLETED -> Unit
            State.PENDING -> try {
                cancel() // Returning requires the real framework cancellation ACK.
            } catch (e: Exception) {
                // Formation can win the race with cancellation. A fresh completion
                // proof, never BUSY/timeout itself, may retire the old operation.
                if (inspect(owned.address) != State.COMPLETED) throw e
            }
            State.UNRELATED -> error("Another peer negotiation/group superseded owned CONNECT")
            State.UNKNOWN -> error("Owned CONNECT completion unconfirmed")
        }
        operation = null
    }
}
