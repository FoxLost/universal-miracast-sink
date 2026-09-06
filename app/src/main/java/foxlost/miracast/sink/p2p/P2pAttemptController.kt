package foxlost.miracast.sink.p2p

/**
 * Pure retry/generation gate for one sink lifecycle.
 *
 * A failed attempt remains invalid until its owner reports that cleanup has
 * completed. This makes it impossible for a delayed framework callback to
 * start the next attempt before the old listener, monitor, or group has been
 * quiesced.
 */
internal class P2pAttemptController(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
    }

    data class Token(val generation: Long, val attemptId: Long, val number: Int)

    sealed class FailureDecision {
        data class Retry(val delayMs: Long, val nextAttemptNumber: Int) : FailureDecision()
        data object Exhausted : FailureDecision()
        data object Ignored : FailureDecision()
    }

    private var generation = 0L
    private var attemptCount = 0
    private var nextAttemptId = 0L
    private var active: Token? = null
    private var cleanupPendingFor: Token? = null

    val attemptsStarted: Int
        get() = attemptCount

    val activeToken: Token?
        get() = active

    fun start(newGeneration: Long): Token {
        generation = newGeneration
        attemptCount = 0
        nextAttemptId = 0L
        active = null
        cleanupPendingFor = null
        return beginNext()
            ?: error("initial attempt cannot be exhausted")
    }

    fun beginNext(): Token? {
        if (active != null || cleanupPendingFor != null || attemptCount >= maxAttempts) return null
        val token = Token(
            generation = generation,
            attemptId = ++nextAttemptId,
            number = ++attemptCount,
        )
        active = token
        return token
    }

    fun accepts(token: Token): Boolean = active == token

    fun fail(token: Token): FailureDecision {
        if (active != token) return FailureDecision.Ignored
        active = null
        cleanupPendingFor = token
        return if (attemptCount < maxAttempts) {
            FailureDecision.Retry(retryDelayMs, attemptCount + 1)
        } else {
            FailureDecision.Exhausted
        }
    }

    /** Marks all asynchronous work from [token] invalid without scheduling retry. */
    fun cancel(token: Token? = active): Boolean {
        if (token != null && active != token && cleanupPendingFor != token) return false
        active = null
        cleanupPendingFor = null
        return true
    }

    /** Must be called after listener/monitor/group cleanup, before [beginNext]. */
    fun completeCleanup(token: Token): Boolean {
        if (cleanupPendingFor != token) return false
        cleanupPendingFor = null
        return true
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_DELAY_MS = 500L
    }
}
