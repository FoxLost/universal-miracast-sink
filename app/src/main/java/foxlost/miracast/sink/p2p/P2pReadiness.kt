package foxlost.miracast.sink.p2p

/** Pure predicate used by the Android P2P readiness poll. */
internal class GroupReadinessGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
    }

    enum class Result { Waiting, Ready, TimedOut }

    fun evaluate(
        elapsedMs: Long,
        groupFormed: Boolean,
        peerPresent: Boolean,
        interfaceUp: Boolean,
        ipv4Present: Boolean,
    ): Result {
        if (groupFormed && peerPresent && interfaceUp && ipv4Present) return Result.Ready
        return if (elapsedMs >= timeoutMs) Result.TimedOut else Result.Waiting
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 6_000L
    }
}
