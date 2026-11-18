package foxlost.miracast.sink.uibc

enum class GestureAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
data class GesturePointer(val pointerId: Int, val x: Float, val y: Float)

/** Android-independent gesture ownership and stable, bounded wire contact identifiers. */
class TouchGestureProcessor(
    capabilities: Set<UibcCapability>,
    private val category: UibcCategory,
) {
    private val multi = UibcCapability.MultiTouch in capabilities
    private val active = linkedMapOf<Int, TouchPoint>()
    private var gesture = false

    init { require(capabilities.isNotEmpty()) }

    fun process(
        action: GestureAction,
        actionIndex: Int,
        pointers: List<GesturePointer>,
        layout: VideoLayout,
        videoWidth: Int,
        videoHeight: Int,
    ): List<TouchEvent> {
        if (action == GestureAction.CANCEL) return reset()
        val result = ArrayList<TouchEvent>(2)
        if (action == GestureAction.DOWN) {
            result.addAll(reset())
            val pointer = pointers.getOrNull(actionIndex) ?: return result
            val mapped = TouchMapper.map(MotionPoint(pointer.x, pointer.y), layout, videoWidth, videoHeight, 0, category, clamp = false)
                ?: return result // Letterbox DOWN does not own a gesture, including subsequent moves.
            active[pointer.pointerId] = mapped
            gesture = true
            result.add(TouchEvent(TouchAction.DOWN, listOf(mapped)))
            return result
        }
        if (!gesture) return result
        // Refresh coordinates of owned contacts, clamping only an active gesture.
        pointers.forEach { pointer ->
            val old = active[pointer.pointerId] ?: return@forEach
            active[pointer.pointerId] = TouchMapper.map(MotionPoint(pointer.x, pointer.y), layout,
                videoWidth, videoHeight, old.pointerId, category, clamp = true)!!
        }
        when (action) {
            GestureAction.POINTER_DOWN -> {
                if (!multi || active.size >= HidTouchCodec.MAX_CONTACTS) return result
                val pointer = pointers.getOrNull(actionIndex) ?: return result
                if (pointer.pointerId in active) return result
                val wireId = (0 until HidTouchCodec.MAX_CONTACTS).first { id -> active.values.none { it.pointerId == id } }
                val mapped = TouchMapper.map(MotionPoint(pointer.x, pointer.y), layout,
                    videoWidth, videoHeight, wireId, category, clamp = false) ?: return result
                active[pointer.pointerId] = mapped
                result.add(TouchEvent(TouchAction.DOWN, active.values.toList()))
            }
            GestureAction.MOVE -> if (active.isNotEmpty()) result.add(TouchEvent(TouchAction.MOVE, active.values.toList()))
            GestureAction.POINTER_UP -> {
                val pointer = pointers.getOrNull(actionIndex) ?: return result
                val released = active.remove(pointer.pointerId) ?: return result
                result.add(TouchEvent(TouchAction.UP, listOf(released)))
                // SingleTouch never promotes a secondary contact to the primary.
            }
            GestureAction.UP -> result.addAll(reset())
            else -> Unit
        }
        return result
    }

    /** Explicit releases for every owned contact, then forget all Android pointer IDs. */
    fun reset(): List<TouchEvent> {
        val releases = if (active.isEmpty()) emptyList() else listOf(TouchEvent(TouchAction.UP, active.values.toList()))
        active.clear()
        gesture = false
        return releases
    }
}
