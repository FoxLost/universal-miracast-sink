package foxlost.miracast.sink

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import foxlost.miracast.sink.p2p.P2pBand

/**
 * Central store for every user-tunable Miracast sink parameter.
 *
 * Values persist in SharedPreferences and are read by the P2P, RTSP and media
 * layers. The P2P/WFD device name defaults to the system-wide "device_name"
 * global setting (Settings → About phone → Device name) so the sink advertises
 * the same identity the user already configured in Android, and can be
 * overridden here without touching the system value.
 *
 * Access via [SettingsManager.get] after [SettingsManager.init] has been called
 * (done once from MainActivity and MiracastService).
 */
object SettingsManager {
    private const val TAG = "MiracastSettings"
    private const val PREFS = "miracast_sink_prefs"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateVideoPreferences(prefs)
        initialized = true
    }

    private fun checkInit() {
        check(initialized) { "SettingsManager.init(context) must be called first" }
    }

    // ---- Keys ----
    const val KEY_DEVICE_NAME_OVERRIDE = "device_name_override"
    const val KEY_MANUFACTURER = "manufacturer"
    const val KEY_RTSP_PORT = "rtsp_port"
    const val KEY_RTP_VIDEO_PORT = "rtp_video_port"
    const val KEY_GO_INTENT_CLIENT = "go_intent_client"
    const val KEY_GO_INTENT_FALLBACK = "go_intent_fallback"
    const val KEY_FORCE_AUTONOMOUS_GO = "force_autonomous_go"
    private const val LEGACY_VIDEO_PROFILE = "video_profile"
    const val KEY_VIDEO_HEIGHT = "video_height"
    const val KEY_VIDEO_FPS = "video_fps"
    const val KEY_WIFI_BAND = "wifi_band"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_UIBC_ENABLED = "uibc_enabled"
    const val KEY_MAX_THROUGHPUT = "max_throughput"
    const val KEY_SECOND_PLAY = "second_play"
    const val KEY_PERSISTENT_LISTEN = "persistent_listen"
    const val KEY_DISCONNECT_WIFI = "disconnect_wifi_in_sink_mode"
    const val KEY_EXT_LISTEN_MS = "ext_listen_ms"

    // ---- Defaults ----
    const val DEFAULT_RTSP_PORT = 7236
    const val DEFAULT_RTP_VIDEO_PORT = 15550
    const val DEFAULT_GO_INTENT_CLIENT = 0
    const val DEFAULT_GO_INTENT_FALLBACK = 8
    const val DEFAULT_MAX_THROUGHPUT = 50

    /**
     * Remove the legacy preset in the same transaction as its replacement. Existing
     * independent choices win, including after an upgrade with partially saved settings.
     */
    internal fun migrateVideoPreferences(preferences: SharedPreferences) {
        if (!preferences.contains(LEGACY_VIDEO_PROFILE)) return
        val legacy = preferences.getInt(LEGACY_VIDEO_PROFILE, 0)
        val edit = preferences.edit()
        if (!preferences.contains(KEY_VIDEO_HEIGHT)) edit.putInt(KEY_VIDEO_HEIGHT, if (legacy == 2) 720 else 1080)
        if (!preferences.contains(KEY_VIDEO_FPS)) edit.putInt(KEY_VIDEO_FPS, if (legacy == 1) 30 else 60)
        edit.remove(LEGACY_VIDEO_PROFILE).apply()
    }

    // ---- Device name ----

    /**
     * System device name from the global settings provider ("device_name").
     * Falls back to Build.MODEL when unavailable.
     */
    fun systemDeviceName(): String {
        checkInit()
        val candidates = listOf("device_name", "bluetooth_name")
        for (key in candidates) {
            try {
                val v = Settings.Global.getString(appContext.contentResolver, key)
                if (!v.isNullOrBlank()) return v.trim()
            } catch (e: Exception) {
                Log.d(TAG, "global settings '$key' unreadable: ${e.message}")
            }
        }
        return Build.MODEL
    }

    /**
     * The device name actually advertised for P2P + WFD + intel_friendly_name.
     * Uses the user override when set, otherwise the global settings name.
     */
    var deviceName: String
        get() {
            checkInit()
            val override = prefs.getString(KEY_DEVICE_NAME_OVERRIDE, "")?.trim()
            return if (!override.isNullOrEmpty()) override else systemDeviceName()
        }
        set(value) {
            checkInit()
            prefs.edit().putString(KEY_DEVICE_NAME_OVERRIDE, value.trim()).apply()
        }

    val deviceNameOverride: String
        get() { checkInit(); return prefs.getString(KEY_DEVICE_NAME_OVERRIDE, "")?.trim().orEmpty() }

    var manufacturer: String
        get() { checkInit(); return prefs.getString(KEY_MANUFACTURER, Build.MANUFACTURER) ?: Build.MANUFACTURER }
        set(value) { checkInit(); prefs.edit().putString(KEY_MANUFACTURER, value).apply() }

    // ---- Network / ports ----
    var rtspPort: Int
        get() { checkInit(); return prefs.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_RTSP_PORT, value).apply() }

    var rtpVideoPort: Int
        get() { checkInit(); return prefs.getInt(KEY_RTP_VIDEO_PORT, DEFAULT_RTP_VIDEO_PORT) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_RTP_VIDEO_PORT, value).apply() }

    /** Effective RTSP control port shared by the WFD IE and all RTSP paths. */
    fun effectiveRtspPort(): Int {
        val port = rtspPort
        return port.takeIf { it in 1..65535 } ?: DEFAULT_RTSP_PORT
    }

    // ---- GO negotiation ----
    var goIntentClient: Int
        get() { checkInit(); return prefs.getInt(KEY_GO_INTENT_CLIENT, DEFAULT_GO_INTENT_CLIENT) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_GO_INTENT_CLIENT, value.coerceIn(0, 15)).apply() }

    var goIntentFallback: Int
        get() { checkInit(); return prefs.getInt(KEY_GO_INTENT_FALLBACK, DEFAULT_GO_INTENT_FALLBACK) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_GO_INTENT_FALLBACK, value.coerceIn(0, 15)).apply() }

    /** When true, the sink becomes autonomous GO (legacy Android-source path). */
    var forceAutonomousGo: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_FORCE_AUTONOMOUS_GO, false) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_FORCE_AUTONOMOUS_GO, value).apply() }

    // ---- Media ----
    var videoHeight: Int
        get() { checkInit(); return if (prefs.getInt(KEY_VIDEO_HEIGHT, 1080) == 720) 720 else 1080 }
        set(value) {
            checkInit()
            require(value == 720 || value == 1080)
            prefs.edit().putInt(KEY_VIDEO_HEIGHT, value).apply()
        }

    var videoFps: Int
        get() { checkInit(); return if (prefs.getInt(KEY_VIDEO_FPS, 60) == 30) 30 else 60 }
        set(value) {
            checkInit()
            require(value == 30 || value == 60)
            prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()
        }

    var wifiBand: P2pBand
        get() { checkInit(); return P2pBand.fromPreference(prefs.getString(KEY_WIFI_BAND, null)) }
        set(value) { checkInit(); prefs.edit().putString(KEY_WIFI_BAND, value.name).apply() }

    var audioEnabled: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_AUDIO_ENABLED, true) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply() }

    /** Allow optional source-negotiated Generic UIBC touch forwarding; off by default. */
    var uibcEnabled: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_UIBC_ENABLED, false) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_UIBC_ENABLED, value).apply() }

    var maxThroughput: Int
        get() { checkInit(); return prefs.getInt(KEY_MAX_THROUGHPUT, DEFAULT_MAX_THROUGHPUT) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_MAX_THROUGHPUT, value).apply() }

    /** Opt-in source quirk only. Standard WFD starts streaming after the first successful PLAY. */
    var secondPlay: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_SECOND_PLAY, false) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_SECOND_PLAY, value).apply() }

    /**
     * Keep wpa_supplicant in a continuous LISTEN state (recommended — required to
     * accept Windows GO negotiation). When off, falls back to a discoverPeers poll.
     */
    var usePersistentListen: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_PERSISTENT_LISTEN, true) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_PERSISTENT_LISTEN, value).apply() }

    /**
     * Disconnect Wi-Fi STA during a sink session to reduce single-radio channel
     * contention with P2P. The on-demand root bridge restores the networks it
     * disabled when the session ends. Extended-listen overrides are separately
     * controlled by extListenMs; no framework-wide hook changes other clients.
     */
    var disconnectWifiInSinkMode: Boolean
        get() { checkInit(); return prefs.getBoolean(KEY_DISCONNECT_WIFI, true) }
        set(value) { checkInit(); prefs.edit().putBoolean(KEY_DISCONNECT_WIFI, value).apply() }

    /**
     * Extended-listen override in ms (period == interval). 0 = OFF (default):
     * leave the framework's native 500/500 in place. On this msm8953 radio,
     * aggressive overrides (10/10, 100/100) cause remain-on-channel thrash
     * that drops the PD-response frame. The supplicant event monitor handles
     * the real Status-1 cause (arming p2pConnect on GO-neg), so the override
     * is only kept as an experiment knob.
     */
    var extListenMs: Int
        get() { checkInit(); return prefs.getInt(KEY_EXT_LISTEN_MS, 0) }
        set(value) { checkInit(); prefs.edit().putInt(KEY_EXT_LISTEN_MS, value.coerceIn(0, 5000)).apply() }

    /** Numeric/enum settings only: never include device identity in diagnostics. */
    internal fun diagnosticSnapshot(): String {
        val height = videoHeight
        return "savedBand=${wifiBand.name} videoWidth=${if (height == 720) 1280 else 1920} " +
            "videoHeight=$height nominalFps=$videoFps localMaxThroughputMbps=$maxThroughput " +
            "rtspPort=$rtspPort rtpPort=$rtpVideoPort audioEnabled=$audioEnabled"
    }

    /** Called only after Save accepts the draft; apply() does not promise disk durability. */
    internal fun logAppliedSettings() {
        Log.i(TAG, "Settings Save applied ${diagnosticSnapshot()}")
    }

    /** Progressive CEA modes bounded by independent user choices and actual AVC decoders. */
    fun videoFormats(): String =
        foxlost.miracast.sink.media.WfdAvcFormats.forPreference(videoHeight, videoFps)
}
