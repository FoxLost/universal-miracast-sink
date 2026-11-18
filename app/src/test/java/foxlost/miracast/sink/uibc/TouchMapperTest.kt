package foxlost.miracast.sink.uibc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMapperTest {
    @Test fun centeredFitProducesLetterboxAndMapsVisibleCorners() {
        val layout = TouchMapper.centeredLayout(1000, 1000, 1920, 1080, crop = false)
        assertEquals(0f, layout.left, 0.01f)
        assertEquals(218.75f, layout.top, 0.01f)
        assertEquals(TouchPoint(4, 0, 0), TouchMapper.map(MotionPoint(0f, 218.75f), layout, 1920, 1080, 4, UibcCategory.HIDC))
        assertNull(TouchMapper.map(MotionPoint(500f, 100f), layout, 1920, 1080, 4, UibcCategory.HIDC, clamp = false))
    }

    @Test fun cropMapsOutsidePixelsToNearestVideoEdge() {
        val layout = TouchMapper.centeredLayout(1000, 1000, 1920, 1080, crop = true)
        assertEquals(TouchPoint(3, 0, 16384), TouchMapper.map(MotionPoint(-500f, 500f), layout, 1920, 1080, 3, UibcCategory.HIDC))
        assertEquals(TouchPoint(3, 32767, 16384), TouchMapper.map(MotionPoint(1500f, 500f), layout, 1920, 1080, 3, UibcCategory.HIDC))
    }

    @Test fun clampsActiveGestureAndRejectsLetterboxDownWhenRequested() {
        val layout = VideoLayout(0f, 250f, 1000f, 500f)
        assertNull(TouchMapper.map(MotionPoint(500f, 100f), layout, 1920, 1080, 1, UibcCategory.HIDC, clamp = false))
        assertEquals(TouchPoint(1, 16384, 0), TouchMapper.map(MotionPoint(500f, 100f), layout, 1920, 1080, 1, UibcCategory.HIDC, clamp = true))
    }

    @Test fun genericCoordinatesRemainNegotiatedVideoPixels() {
        val layout = VideoLayout(0f, 0f, 1000f, 1000f)
        assertEquals(TouchPoint(0, 960, 540), TouchMapper.map(MotionPoint(500f, 500f), layout, 1920, 1080, 0, UibcCategory.GENERIC))
        assertEquals(TouchPoint(0, 1919, 1079), TouchMapper.map(MotionPoint(1200f, 1200f), layout, 1920, 1080, 0, UibcCategory.GENERIC))
    }
}
