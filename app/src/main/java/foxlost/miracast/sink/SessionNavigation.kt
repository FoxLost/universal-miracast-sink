package foxlost.miracast.sink

/** Service-authorized media epoch; never reconstructed from an Activity intent. */
data class PlayerSessionSnapshot(
    val generation: Long,
    val attemptId: Long,
    val rtpPort: Int,
    val rtcpPort: Int?,
    val ssrc: Long? = null,
)

/** Called under the service monitor. Owner leases reject late bind/destroy callbacks. */
internal class SessionNavigation {
    var snapshot: PlayerSessionSnapshot? = null
        private set
    private var owner: Long? = null
    private var nextOwner = 0L
    private var bound = false
    private var streaming = false
    val resumable: PlayerSessionSnapshot?
        get() = snapshot?.takeIf { streaming && bound && owner != null }

    fun prepare(value: PlayerSessionSnapshot) {
        clear()
        snapshot = value
    }

    fun claim(generation: Long): Long? {
        if (snapshot?.generation != generation || owner != null) return null
        return (++nextOwner).also { owner = it }
    }

    fun transport(value: PlayerSessionSnapshot): Boolean {
        val old = snapshot ?: return false
        if (old.generation != value.generation || old.attemptId != value.attemptId ||
            old.rtpPort != value.rtpPort || old.rtcpPort != value.rtcpPort) return false
        snapshot = value
        return true
    }

    fun markBound(generation: Long, lease: Long): Boolean {
        if (!owns(generation, lease)) return false
        bound = true
        return true
    }

    fun markStreaming(generation: Long, attemptId: Long): Boolean {
        if (snapshot?.let { it.generation == generation && it.attemptId == attemptId } != true) return false
        streaming = true
        return true
    }

    fun owns(generation: Long, lease: Long): Boolean =
        snapshot?.generation == generation && owner == lease

    /** True means repair media only, retaining the existing RTSP connection. */
    fun release(generation: Long, lease: Long): Boolean {
        if (!owns(generation, lease)) return false
        owner = null
        bound = false
        return streaming
    }

    fun clear() {
        snapshot = null
        owner = null
        bound = false
        streaming = false
    }
}

internal class DashboardBackWindow(private val windowMs: Long = 2_000L) {
    private var armedAt: Long? = null
    fun reset() { armedAt = null }
    /** True only for a second completed Back within the monotonic window. */
    fun press(nowMs: Long): Boolean {
        val elapsed = armedAt?.let { nowMs - it }
        if (elapsed != null && elapsed in 0 until windowMs) {
            reset()
            return true
        }
        armedAt = nowMs
        return false
    }
}
