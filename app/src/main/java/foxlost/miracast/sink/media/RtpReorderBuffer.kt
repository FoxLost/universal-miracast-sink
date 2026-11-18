package foxlost.miracast.sink.media

/** Single-consumer reorder window. Time is supplied by the owner, including on idle ticks. */
class RtpReorderBuffer<T : Any>(
    private val window: Int = 64,
    private val timeoutNs: Long = 40_000_000L,
    private val deliver: (T) -> Unit,
    private val discard: (T) -> Unit,
    private val onLoss: (Long) -> Unit,
) {
    init { require(window in 2..16384 && window and (window - 1) == 0); require(timeoutNs > 0) }
    private class Slot<T> {
        var sequence = -1
        var arrival = 0L
        var value: T? = null
    }
    private val pending = Array(window) { Slot<T>() }
    private var expected = -1
    var delivered = 0L; private set
    var lost = 0L; private set
    var duplicates = 0L; private set
    var buffered = 0; private set

    fun offer(sequence: Int, value: T, nowNs: Long) {
        val seq = sequence and 0xffff
        if (expected < 0) expected = seq
        val distance = (seq - expected) and 0xffff
        if (distance >= 32768) { duplicates++; discard(value); return }
        if (distance >= window) skipTo((seq - window + 1) and 0xffff)
        val slot = pending[seq and (window - 1)]
        if (slot.value != null) { duplicates++; discard(value); return }
        slot.sequence = seq; slot.arrival = nowNs; slot.value = value; buffered++
        drain()
        tick(nowNs)
    }

    fun tick(nowNs: Long) {
        while (buffered > 0) {
            var oldest = Long.MAX_VALUE
            var nearest = 65536
            for (slot in pending) if (slot.value != null) {
                oldest = minOf(oldest, slot.arrival)
                nearest = minOf(nearest, (slot.sequence - expected) and 0xffff)
            }
            if (nowNs - oldest < timeoutNs) return
            skipTo((expected + nearest) and 0xffff)
            drain()
        }
    }

    private fun skipTo(target: Int) {
        var missing = 0L
        while (expected != target) {
            if (buffered == 0) {
                missing += (target - expected) and 0xffff
                expected = target
                break
            }
            val slot = pending[expected and (window - 1)]
            val value = slot.value
            if (value != null && slot.sequence == expected) {
                if (missing > 0) { reportLoss(missing); missing = 0 }
                slot.value = null; buffered--; delivered++; deliver(value)
            } else missing++
            expected = (expected + 1) and 0xffff
        }
        if (missing > 0) reportLoss(missing)
    }

    private fun reportLoss(count: Long) { lost += count; onLoss(count) }
    private fun drain() {
        while (expected >= 0) {
            val slot = pending[expected and (window - 1)]
            val value = slot.value ?: return
            if (slot.sequence != expected) return
            slot.value = null; buffered--; expected = (expected + 1) and 0xffff
            delivered++; deliver(value)
        }
    }
    fun clear() {
        for (slot in pending) { slot.value?.let(discard); slot.value = null }
        buffered = 0; expected = -1
    }
}
