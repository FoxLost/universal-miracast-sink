package foxlost.miracast.sink.uibc

import org.junit.Assert.*
import org.junit.Test

class TouchGestureProcessorTest {
    private val layout = VideoLayout(0f, 100f, 100f, 100f)
    private fun processor(multi: Boolean = true) = TouchGestureProcessor(setOf(if (multi) UibcCapability.MultiTouch else UibcCapability.SingleTouch), UibcCategory.GENERIC)
    private fun feed(p: TouchGestureProcessor, action: GestureAction, index: Int = 0, vararg points: GesturePointer) =
        p.process(action, index, points.toList(), layout, 1920, 1080)
    private fun point(id: Int, x: Float = 50f, y: Float = 150f) = GesturePointer(id, x, y)

    @Test fun letterboxStartIsIgnoredEvenAfterMovingInside() {
        val p = processor()
        assertTrue(feed(p, GestureAction.DOWN, 0, point(1, y = 50f)).isEmpty())
        assertTrue(feed(p, GestureAction.MOVE, 0, point(1)).isEmpty())
        assertTrue(feed(p, GestureAction.UP, 0, point(1)).isEmpty())
        assertTrue(p.reset().isEmpty())
    }

    @Test fun activeMovementClampsAndSingleSecondaryReleaseDoesNotReleasePrimary() {
        val p = processor(false)
        val down = feed(p, GestureAction.DOWN, 0, point(12)).single()
        assertEquals(0, down.points.single().pointerId)
        assertTrue(feed(p, GestureAction.POINTER_DOWN, 1, point(12), point(13)).isEmpty())
        assertTrue(feed(p, GestureAction.POINTER_UP, 1, point(12), point(13)).isEmpty())
        val move = feed(p, GestureAction.MOVE, 0, point(12, 200f, 50f)).single()
        assertEquals(TouchPoint(0, 1919, 0), move.points.single())
        assertEquals(TouchAction.UP, feed(p, GestureAction.UP, 0, point(12)).single().action)
        assertTrue(p.reset().isEmpty())
    }

    @Test fun singlePrimaryReleaseDoesNotPromoteStillHeldSecondary() {
        val p = processor(false)
        feed(p, GestureAction.DOWN, 0, point(1))
        feed(p, GestureAction.POINTER_DOWN, 1, point(1), point(2))
        assertEquals(TouchAction.UP, feed(p, GestureAction.POINTER_UP, 0, point(1), point(2)).single().action)
        assertTrue(feed(p, GestureAction.MOVE, 0, point(2)).isEmpty())
        assertTrue(feed(p, GestureAction.UP, 0, point(2)).isEmpty())
    }

    @Test fun cancelReleasesAllStableContactsAndRepeatedDownReleasesOldGesture() {
        val p = processor()
        feed(p, GestureAction.DOWN, 0, point(300))
        val second = feed(p, GestureAction.POINTER_DOWN, 1, point(300), point(900)).single()
        assertEquals(listOf(0, 1), second.points.map { it.pointerId })
        val releases = feed(p, GestureAction.CANCEL).single()
        assertEquals(TouchAction.UP, releases.action)
        assertEquals(listOf(0, 1), releases.points.map { it.pointerId })
        assertTrue(p.reset().isEmpty())
        feed(p, GestureAction.DOWN, 0, point(3))
        val replacement = feed(p, GestureAction.DOWN, 0, point(4))
        assertEquals(listOf(TouchAction.UP, TouchAction.DOWN), replacement.map { it.action })
    }

    @Test fun hidCoordinatesUseDescriptorLogicalUnitsNotVideoPixels() {
        val p = TouchGestureProcessor(setOf(UibcCapability.SingleTouch), UibcCategory.HIDC)
        val result = feed(p, GestureAction.DOWN, 0, point(1, 100f, 200f)).single()
        assertEquals(TouchPoint(0, 32767, 32767), result.points.single())
    }

    @Test fun contactBeyondMaximumIsSuppressedUntilItsPhysicalLifetimeEnds() {
        val p = processor()
        val held = mutableListOf(point(0))
        feed(p, GestureAction.DOWN, 0, *held.toTypedArray())
        for (id in 1..9) {
            held.add(point(id))
            assertEquals(id + 1, feed(p, GestureAction.POINTER_DOWN, id, *held.toTypedArray()).single().points.size)
        }
        held.add(point(10))
        assertTrue(feed(p, GestureAction.POINTER_DOWN, 10, *held.toTypedArray()).isEmpty())
        feed(p, GestureAction.POINTER_UP, 0, *held.toTypedArray())
        held.removeAt(0)
        val move = feed(p, GestureAction.MOVE, 0, *held.toTypedArray()).single()
        assertEquals(9, move.points.size) // suppressed finger 10 is not promoted
        held.add(point(11))
        val admitted = feed(p, GestureAction.POINTER_DOWN, held.lastIndex, *held.toTypedArray()).single()
        assertEquals(10, admitted.points.size) // a genuinely new contact may take the free slot
        assertEquals(10, p.reset().single().points.size)
    }
}
