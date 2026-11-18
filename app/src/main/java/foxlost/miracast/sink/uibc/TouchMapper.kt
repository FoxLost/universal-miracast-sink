package foxlost.miracast.sink.uibc

import kotlin.math.roundToInt

/** Coordinates of the actual displayed video rectangle in Activity/window space. */
data class VideoLayout(val left: Float, val top: Float, val width: Float, val height: Float) {
    init { require(width > 0f && height > 0f) { "video rectangle must be positive" } }
}

data class MotionPoint(val x: Float, val y: Float)

object TouchMapper {
    fun map(
        point: MotionPoint,
        layout: VideoLayout,
        videoWidth: Int,
        videoHeight: Int,
        pointerId: Int,
        category: UibcCategory,
        clamp: Boolean = true,
    ): TouchPoint? {
        require(videoWidth > 0 && videoHeight > 0)
        val inside = point.x >= layout.left && point.x <= layout.left + layout.width &&
            point.y >= layout.top && point.y <= layout.top + layout.height
        if (!inside && !clamp) return null
        val localX = ((point.x - layout.left) / layout.width).coerceIn(0f, 1f)
        val localY = ((point.y - layout.top) / layout.height).coerceIn(0f, 1f)
        return TouchPoint(
            pointerId = pointerId,
            x = (localX * if (category == UibcCategory.HIDC) HidTouchCodec.LOGICAL_MAX else minOf(videoWidth - 1, 65535)).roundToInt().coerceIn(0, 65535),
            y = (localY * if (category == UibcCategory.HIDC) HidTouchCodec.LOGICAL_MAX else minOf(videoHeight - 1, 65535)).roundToInt().coerceIn(0, 65535),
        )
    }

    /** Inverts the centered fit/crop transform used by PlayerActivity. */
    fun centeredLayout(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        crop: Boolean,
    ): VideoLayout {
        require(containerWidth > 0 && containerHeight > 0 && videoWidth > 0 && videoHeight > 0)
        val sx = containerWidth.toFloat() / videoWidth
        val sy = containerHeight.toFloat() / videoHeight
        val scale = if (crop) maxOf(sx, sy) else minOf(sx, sy)
        val width = videoWidth * scale
        val height = videoHeight * scale
        return VideoLayout(
            left = (containerWidth - width) / 2f,
            top = (containerHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

}
