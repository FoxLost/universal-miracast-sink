package foxlost.miracast.sink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.os.SystemClock
import android.widget.Toast
import foxlost.miracast.sink.media.MediaDecoderPipeline
import foxlost.miracast.sink.media.RtpEndpointRegistry
import foxlost.miracast.sink.media.RtpReceiver
import foxlost.miracast.sink.media.TsDemuxer
import foxlost.miracast.sink.uibc.GestureAction
import foxlost.miracast.sink.uibc.GesturePointer
import foxlost.miracast.sink.uibc.TouchGestureProcessor
import foxlost.miracast.sink.uibc.TouchAction
import foxlost.miracast.sink.uibc.UibcSession
import foxlost.miracast.sink.uibc.UibcSessionRegistry
import java.util.concurrent.atomic.AtomicLong
import foxlost.miracast.sink.uibc.VideoLayout
import kotlin.math.max
import kotlin.math.min

class PlayerActivity : ComponentActivity(), TextureView.SurfaceTextureListener {
    companion object {
        private var current = java.lang.ref.WeakReference<PlayerActivity>(null)

        /** Main-thread retirement includes sockets, decoder and touch state, not just finish(). */
        internal fun retireSession(generation: Long, onComplete: (Boolean) -> Unit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val retire = Runnable {
                val player = current.get()
                try {
                    if (player != null && player.sessionGeneration() == generation) {
                        player.retireMedia()
                        player.finish()
                    }
                    onComplete(true)
                } catch (e: Exception) {
                    Log.e("MiracastApp", "Player retirement failed", e)
                    onComplete(false)
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) retire.run()
            else if (!main.post(retire)) onComplete(false)
        }
    }
    @Volatile private var decoderPipeline: MediaDecoderPipeline? = null
    private val surfaceMediaLock = Any()
    private val beforeSurface = foxlost.miracast.sink.media.SurfaceVideoBuffer()
    private var videoSurface: android.view.Surface? = null
    private var rtpReceiver: RtpReceiver? = null
    private var textureView: TextureView? = null
    private var container: FrameLayout? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var touchSession: UibcSession? = null
    private var touchProcessor: TouchGestureProcessor? = null
    private var capturedTouchSession: UibcSession? = null
    private val lastRecoveryRequestNanos = AtomicLong(Long.MIN_VALUE)
    private val dashboardBack = DashboardBackWindow()
    private var backToast: Toast? = null
    private var ownerLease: Long? = null
    private var endpoint: PlayerSessionSnapshot? = null
    private val sessionEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, -1L) != sessionGeneration()) return
            // A queued end from a previous attempt may share this generation.
            if (ownerLease?.let { MiracastService.ownsPlayer(sessionGeneration(), it) } == true) return
            Log.d("MiracastApp", "Session end broadcast received, finishing PlayerActivity")
            retireMedia()
            finish()
        }
    }

    private val transportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MiracastService.ACTION_TRANSPORT_NEGOTIATED) return
            if (intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, -1L) != sessionGeneration()) return
            val lease = ownerLease ?: return
            endpoint = MiracastService.playerSnapshot(sessionGeneration(), lease) ?: return
            rtpReceiver?.expectedSsrc = endpoint?.ssrc
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val generation = sessionGeneration()
        val existing = current.get()
        if (existing != null && existing !== this && !existing.isFinishing) {
            val lease = existing.ownerLease
            if (lease != null && MiracastService.ownsPlayer(existing.sessionGeneration(), lease)) {
                MiracastService.returnToPlayer(this, existing.sessionGeneration())
                finish()
                return
            }
            // A retry can reuse the service generation but never the owner lease.
            existing.retireMedia()
            existing.finish()
        }
        val claim = MiracastService.claimPlayer(generation)
        if (claim == null) {
            finish()
            return
        }
        ownerLease = claim.first
        endpoint = claim.second
        current = java.lang.ref.WeakReference(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dashboardBack.press(SystemClock.elapsedRealtime())) {
                    backToast?.cancel()
                    backToast = Toast.makeText(this@PlayerActivity,
                        "Press Back again to return to dashboard; the session continues",
                        Toast.LENGTH_SHORT).also { it.show() }
                    return
                }
                backToast?.cancel()
                resetTouchGesture()
                startActivity(Intent(this@PlayerActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        })
        androidx.core.content.ContextCompat.registerReceiver(
            this, sessionEndReceiver, IntentFilter("foxlost.miracast.SESSION_END"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, transportReceiver, IntentFilter(MiracastService.ACTION_TRANSPORT_NEGOTIATED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (!RtpEndpointRegistry.isActive(sessionGeneration().toString())) {
            finish()
            return
        }
        SettingsManager.init(this)
        if (!startMediaReceiver()) {
            finish()
            return
        }
        title = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        }
        textureView = TextureView(this).apply {
            surfaceTextureListener = this@PlayerActivity
        }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textureView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
        }
        setContentView(container)
        hideSystemBars()
        container?.setOnTouchListener { _, event -> forwardTouch(event) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val lease = ownerLease
        if (lease == null || !MiracastService.ownsPlayer(sessionGeneration(), lease)) {
            // singleTop root launches may reach an invalidated Activity from an
            // earlier attempt. Recreate in place so onCreate claims the new epoch
            // only after onDestroy has synchronously closed the old sockets.
            setIntent(intent)
            recreate()
            return
        }
        // REORDER_TO_FRONT preserves this Activity and its socket/epoch ownership.
        // Never replace its authoritative endpoint with untrusted/stale extras.
        dashboardBack.reset()
        resetTouchGesture()
        if (intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, -1L) == sessionGeneration()) {
            ownerLease?.let { endpoint = MiracastService.playerSnapshot(sessionGeneration(), it) }
            rtpReceiver?.expectedSsrc = endpoint?.ssrc
            requestVideoRecovery()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Edge Back may deliver DOWN then CANCEL before the system callback.
        // Only a completed ordinary touch disarms the previous Back warning.
        if (event.actionMasked == MotionEvent.ACTION_UP) dashboardBack.reset()
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_BACK) dashboardBack.reset()
        return super.onKeyDown(keyCode, event)
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val session = UibcSessionRegistry.session(sessionGeneration())
        if (session == null) {
            resetTouchGesture()
            return false
        }
        if (session !== touchSession) {
            resetTouchGesture()
            val endpoint = session.endpoint ?: return false
            touchSession = session
            touchProcessor = TouchGestureProcessor(endpoint.capabilities, endpoint.category)
        }
        if (videoWidth <= 0 || videoHeight <= 0) return true
        val tv = textureView ?: return true
        if (tv.width <= 0 || tv.height <= 0) return true
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> GestureAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> GestureAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> GestureAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> GestureAction.POINTER_UP
            MotionEvent.ACTION_UP -> GestureAction.UP
            MotionEvent.ACTION_CANCEL -> GestureAction.CANCEL
            else -> return true
        }
        val pointers = (0 until event.pointerCount).map {
            GesturePointer(event.getPointerId(it), event.getX(it), event.getY(it))
        }
        val layout = VideoLayout(tv.left.toFloat(), tv.top.toFloat(), tv.width.toFloat(), tv.height.toFloat())
        val events = touchProcessor?.process(action, event.actionIndex, pointers, layout, videoWidth, videoHeight)
            ?: return true
        for (touch in events) {
            if (touch.action == TouchAction.DOWN && capturedTouchSession !== session) {
                capturedTouchSession = session
                Log.i("MiracastUIBC", "UIBC first touch captured generation=${sessionGeneration()}")
            }
            session.submit(touch)
        }
        return true
    }

    private fun resetTouchGesture() {
        touchProcessor?.reset()
        touchSession?.reset()
        touchProcessor = null
        touchSession = null
    }

    private fun requestVideoRecovery() {
        val lease = ownerLease ?: return
        if (!MiracastService.ownsPlayer(sessionGeneration(), lease)) return
        val now = System.nanoTime()
        val previous = lastRecoveryRequestNanos.get()
        if (previous != Long.MIN_VALUE && now - previous < 1_000_000_000L) return
        if (!lastRecoveryRequestNanos.compareAndSet(previous, now)) return
        try {
            startService(Intent(this, MiracastService::class.java).apply {
                action = MiracastService.ACTION_REQUEST_IDR
                putExtra(MiracastService.EXTRA_SESSION_GENERATION, sessionGeneration())
            })
        } catch (e: IllegalStateException) {
            Log.w("MiracastApp", "Cannot request recovery after playback stopped", e)
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardBack.reset()
        resetTouchGesture()
        if (ownerLease != null) requestVideoRecovery()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onPause() {
        dashboardBack.reset()
        backToast?.cancel()
        resetTouchGesture()
        super.onPause()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        releaseDecoder(clearPending = false)
        val surface = android.view.Surface(st)
        videoSurface = surface
        st.setDefaultBufferSize(1920, 1080)
        val pipeline = MediaDecoderPipeline(
            surface,
            onVideoSizeChanged = { w, h ->
                runOnUiThread { if (videoSurface === surface) onVideoSizeKnown(w, h) }
            },
            onRecoveryNeeded = ::requestVideoRecovery,
        )
        synchronized(surfaceMediaLock) {
            decoderPipeline = pipeline
            beforeSurface.drain(pipeline::signalVideoLoss, pipeline::feedVideoNalu)
        }
        requestVideoRecovery()
    }

    private fun releaseDecoder(clearPending: Boolean = true) {
        val pipeline = synchronized(surfaceMediaLock) {
            val old = decoderPipeline
            decoderPipeline = null
            if (clearPending) beforeSurface.clear()
            old
        }
        val surface = videoSurface
        videoSurface = null
        pipeline?.release()
        surface?.release()
    }

    /**
     * Bind RTP/RTCP before RTSP SETUP/PLAY. Sources are allowed to send the
     * first datagram immediately after SETUP, before a surface is available.
     */
    private fun sessionGeneration(): Long =
        intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, 0L)

    private fun startMediaReceiver(): Boolean {
        val token = sessionGeneration().toString()
        return try {
            val tsDemuxer = TsDemuxer(
                onVideoNaluExtracted = { nalu, pts ->
                    synchronized(surfaceMediaLock) {
                        val pipeline = decoderPipeline
                        if (pipeline == null) beforeSurface.hold(nalu, pts) else pipeline.feedVideoNalu(nalu, pts)
                    }
                },
                onAudioFrameExtracted = { pcm, sr, ch, pts -> decoderPipeline?.playPcmAudio(pcm, sr, ch, pts) },
                onDiscontinuity = ::requestVideoRecovery,
            )
            val snapshot = requireNotNull(endpoint)
            val rtpPort = snapshot.rtpPort
            val rtcpPort = snapshot.rtcpPort
            val receiver = RtpReceiver(
                rtpPort = rtpPort,
                tsDemuxer = tsDemuxer,
                rtcpPort = rtcpPort,
                sessionToken = token,
                onDiscontinuity = ::requestVideoRecovery,
                expectedSsrc = snapshot.ssrc,
            )
            val lease = requireNotNull(ownerLease)
            rtpReceiver = receiver
            kotlin.concurrent.thread(name = "PlayerReceiverStartup") {
                try {
                    if (!MiracastService.ownsPlayer(snapshot.generation, lease)) {
                        receiver.stop()
                        return@thread
                    }
                    receiver.start()
                    if (!MiracastService.ownsPlayer(snapshot.generation, lease)) {
                        receiver.stop()
                        return@thread
                    }
                    check(receiver.boundRtpPort == rtpPort && receiver.boundRtcpPort == rtcpPort) {
                        "Receiver did not bind the authorized endpoints"
                    }
                    MiracastService.playerBound(snapshot.generation, lease)
                    Log.d("MiracastApp", "RTP/RTCP receiver bound on $rtpPort/$rtcpPort before PLAY generation=$token")
                } catch (e: Exception) {
                    receiver.stop()
                    runOnUiThread {
                        if (rtpReceiver !== receiver) return@runOnUiThread
                        rtpReceiver = null
                        Log.e("MiracastApp", "RTP receiver startup failed generation=$token", e)
                        MiracastService.playerFailed(snapshot.generation, lease, "Media receiver bind failed: ${e.message}")
                        finish()
                    }
                }
            }
            true
        } catch (e: Exception) {
            try { rtpReceiver?.stop() } catch (_: Exception) {}
            rtpReceiver = null
            Log.e("MiracastApp", "RTP receiver startup failed generation=$token", e)
            ownerLease?.let { MiracastService.playerFailed(sessionGeneration(), it, "Media receiver bind failed: ${e.message}") }
            false
        }
    }

    private fun onVideoSizeKnown(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (videoWidth == w && videoHeight == h)) return
        resetTouchGesture()
        videoWidth = w; videoHeight = h
        Log.i("MiracastApp", "Video dimensions: ${w}x${h}")
        textureView?.surfaceTexture?.setDefaultBufferSize(w, h)
        adjustViewSize()
    }
    private fun adjustViewSize() {
        val tv = textureView ?: return
        val parent = container ?: return
        val pw = parent.width; val ph = parent.height
        if (pw <= 0 || ph <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            tv.post { adjustViewSize() }
            return
        }
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val scale = if (isLandscape) min(pw.toFloat() / videoWidth, ph.toFloat() / videoHeight)
                    else max(pw.toFloat() / videoWidth, ph.toFloat() / videoHeight)
        val vw = (videoWidth * scale).toInt()
        val vh = (videoHeight * scale).toInt()
        tv.layoutParams = FrameLayout.LayoutParams(vw, vh).apply { gravity = Gravity.CENTER }
        tv.requestLayout()
        parent.requestLayout()
        Log.d("MiracastApp", "View resized: video=${videoWidth}x${videoHeight} view=${vw}x${vh} parent=${pw}x${ph} landscape=$isLandscape")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        adjustViewSize()
    }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        resetTouchGesture()
        releaseDecoder()
        return true
    }

    private fun retireMedia() {
        resetTouchGesture()
        rtpReceiver?.stop()
        rtpReceiver = null
        releaseDecoder()
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        retireMedia()
        ownerLease?.let { MiracastService.playerDestroyed(sessionGeneration(), it) }
        ownerLease = null
        super.onDestroy()
        try { unregisterReceiver(sessionEndReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(transportReceiver) } catch (_: Exception) {}
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}


    override fun onConfigurationChanged(newConfig: Configuration) {
        resetTouchGesture()
        super.onConfigurationChanged(newConfig)
        textureView?.post { textureView?.post { adjustViewSize() } }
    }
}
