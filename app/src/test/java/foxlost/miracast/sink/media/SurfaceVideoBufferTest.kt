package foxlost.miracast.sink.media

import org.junit.Assert.*
import org.junit.Test

class SurfaceVideoBufferTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, type.toByte(), 1, 2, 3)

    @Test fun initialParameterSetsAndLatestIdrSurviveBeforeTheSurfaceWithoutReplayingPFrames() {
        val buffer = SurfaceVideoBuffer()
        val sps = nal(7); val pps = nal(8); val idr = nal(5)
        buffer.hold(sps, 1); buffer.hold(pps, 2); buffer.hold(nal(1), 3)
        buffer.hold(nal(5), 3); buffer.hold(idr, 4)
        val output = mutableListOf<Pair<ByteArray, Long>>()
        buffer.drain { bytes, pts -> output += bytes to pts }
        assertEquals(listOf(1L, 2L, 4L), output.map { it.second })
        assertSame(sps, output[0].first); assertSame(pps, output[1].first); assertSame(idr, output[2].first)
        buffer.drain { _, _ -> fail("Consumed units must not be replayed") }
    }
    @Test fun combinedAccessUnitIsDeliveredOnceAndNewParameterSetsDiscardAnOldIdr() {
        val buffer = SurfaceVideoBuffer()
        val combined = nal(7) + nal(8) + nal(5)
        buffer.hold(combined, 10)
        val output = mutableListOf<Long>()
        buffer.drain { _, pts -> output += pts }
        assertEquals(listOf(10L), output)
        buffer.hold(nal(7), 20); buffer.hold(nal(5), 21); buffer.hold(nal(7), 22)
        output.clear()
        buffer.drain { _, pts -> output += pts }
        assertEquals(listOf(22L), output)
    }
    @Test fun droppedPredictiveFramesRequireRecoveryBeforeParameterReplayInsteadOfUsingStaleReferences() {
        val buffer = SurfaceVideoBuffer()
        buffer.hold(nal(7), 1); buffer.hold(nal(8), 2)
        buffer.hold(nal(5), 3); buffer.hold(nal(1), 4)
        val events = mutableListOf<Long>()
        buffer.drain(onGap = { events += -1L }) { _, pts -> events += pts }
        assertEquals(listOf(-1L, 1L, 2L), events)
    }
}
