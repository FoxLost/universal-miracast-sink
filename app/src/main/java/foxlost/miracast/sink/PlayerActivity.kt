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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import foxlost.miracast.sink.media.MediaDecoderPipeline
import foxlost.miracast.sink.media.RtpEndpointRegistry
import foxlost.miracast.sink.media.RtpReceiver
import foxlost.miracast.sink.media.TsDemuxer
import kotlin.math.max
import kotlin.math.min

class PlayerActivity : ComponentActivity(), TextureView.SurfaceTextureListener {
    private var decoderPipeline: MediaDecoderPipeline? = null
    private var rtpReceiver: RtpReceiver? = null
    private var textureView: TextureView? = null
    private var container: FrameLayout? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private val sessionEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MiracastApp", "Session end broadcast received, finishing PlayerActivity")
            finish()
        }
    }

    private val transportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MiracastService.ACTION_TRANSPORT_NEGOTIATED) return
            if (intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, -1L) != sessionGeneration()) return
            if (!intent.hasExtra(MiracastService.EXTRA_SSRC)) return
            rtpReceiver?.expectedSsrc = intent.getLongExtra(MiracastService.EXTRA_SSRC, 0L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        startMediaReceiver()
        title = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        }
        textureView = TextureView(this).apply { surfaceTextureListener = this@PlayerActivity }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textureView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
        }
        setContentView(container)
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                sessionEndReceiver,
                IntentFilter("foxlost.miracast.SESSION_END"),
                Context.RECEIVER_NOT_EXPORTED,
            )
            registerReceiver(
                transportReceiver,
                IntentFilter(MiracastService.ACTION_TRANSPORT_NEGOTIATED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(sessionEndReceiver, IntentFilter("foxlost.miracast.SESSION_END"))
            @Suppress("DEPRECATION")
            registerReceiver(transportReceiver, IntentFilter(MiracastService.ACTION_TRANSPORT_NEGOTIATED))
        }
    }

    override fun onResume() {
        super.onResume()
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

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val surface = android.view.Surface(st)
        st.setDefaultBufferSize(1920, 1080)

        decoderPipeline = MediaDecoderPipeline(surface) { w, h ->
            runOnUiThread { onVideoSizeKnown(w, h) }
        }
        decoderPipeline?.initVideoDecoder()
    }

    /**
     * Bind RTP/RTCP before RTSP SETUP/PLAY. Sources are allowed to send the
     * first datagram immediately after SETUP, before a surface is available.
     */
    private fun sessionGeneration(): Long =
        intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, 0L)

    private fun startMediaReceiver() {
        val token = sessionGeneration().toString()
        try {
            val tsDemuxer = TsDemuxer(
                onVideoNaluExtracted = { nalu, pts -> decoderPipeline?.feedVideoNalu(nalu, pts) },
                onAudioFrameExtracted = { pcm, sr, ch -> decoderPipeline?.playPcmAudio(pcm, sr, ch) },
            )
            val rtpPort = intent.getIntExtra(MiracastService.EXTRA_RTP_PORT, SettingsManager.rtpVideoPort)
            val rtcpPort = intent.getIntExtra(MiracastService.EXTRA_RTCP_PORT, rtpPort + 1)
                .takeIf { it in 1..65535 }
            rtpReceiver = RtpReceiver(
                rtpPort = rtpPort,
                tsDemuxer = tsDemuxer,
                rtcpPort = rtcpPort,
                sessionToken = token,
            )
            rtpReceiver?.start()
            Log.d("MiracastApp", "RTP/RTCP receiver starting on $rtpPort/$rtcpPort before PLAY generation=$token")
        } catch (e: Exception) {
            RtpEndpointRegistry.clear(token)
            try { rtpReceiver?.stop() } catch (_: Exception) {}
            rtpReceiver = null
            Log.e("MiracastApp", "RTP receiver startup failed generation=$token", e)
        }
    }

    private fun onVideoSizeKnown(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (videoWidth == w && videoHeight == h)) return
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
        decoderPipeline?.release()
        decoderPipeline = null
        return true
    }

    override fun onDestroy() {
        rtpReceiver?.stop()
        rtpReceiver = null
        decoderPipeline?.release()
        decoderPipeline = null
        super.onDestroy()
        try { unregisterReceiver(sessionEndReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(transportReceiver) } catch (_: Exception) {}
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textureView?.post { textureView?.post { adjustViewSize() } }
    }
}
