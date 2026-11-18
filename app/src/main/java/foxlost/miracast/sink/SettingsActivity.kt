package foxlost.miracast.sink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import foxlost.miracast.sink.p2p.P2pBand

/** Edits a recreation-safe local draft; only Save writes the settings store. */
class SettingsActivity : ComponentActivity() {
    private lateinit var reset: NavigationReset
    private var entryClean by mutableStateOf(false)
    private var exitAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        title = "Settings"
        reset = ViewModelProvider(this)[NavigationReset::class.java]
        exitAction = savedInstanceState?.getString("settings_exit_action")
        if (!reset.pending && reset.completedAction == null) {
            // Direct launches and process restoration must not inherit an active sink.
            reset.request(exitAction ?: "enter_settings")
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF93C5FD),
                background = androidx.compose.ui.graphics.Color(0xFF0F172A),
                surface = androidx.compose.ui.graphics.Color(0xFF1E293B),
            )) {
                ResetCompletion(reset) { action ->
                    if (action == "enter_settings") entryClean = true else finish()
                }
                SettingsForm()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exitAction?.let { outState.putString("settings_exit_action", it) }
        super.onSaveInstanceState(outState)
    }

    private fun close() {
        if (reset.pending || reset.completedAction != null) return
        // A saved draft is already committed; retry its acknowledgement, not another Save.
        exitAction = exitAction ?: "close"
        reset.request(exitAction!!)
    }

    @Composable
    private fun SettingsForm() {
        var name by rememberSaveable { mutableStateOf(SettingsManager.deviceNameOverride) }
        var manufacturer by rememberSaveable { mutableStateOf(SettingsManager.manufacturer) }
        var rtsp by rememberSaveable { mutableStateOf(SettingsManager.rtspPort.toString()) }
        var rtp by rememberSaveable { mutableStateOf(SettingsManager.rtpVideoPort.toString()) }
        var client by rememberSaveable { mutableStateOf(SettingsManager.goIntentClient.toString()) }
        var fallback by rememberSaveable { mutableStateOf(SettingsManager.goIntentFallback.toString()) }
        var forceGo by rememberSaveable { mutableStateOf(SettingsManager.forceAutonomousGo) }
        var persistentListen by rememberSaveable { mutableStateOf(SettingsManager.usePersistentListen) }
        var disconnectWifi by rememberSaveable { mutableStateOf(SettingsManager.disconnectWifiInSinkMode) }
        var extListen by rememberSaveable { mutableStateOf(SettingsManager.extListenMs.toString()) }
        var audio by rememberSaveable { mutableStateOf(SettingsManager.audioEnabled) }
        var uibc by rememberSaveable { mutableStateOf(SettingsManager.uibcEnabled) }
        var secondPlay by rememberSaveable { mutableStateOf(SettingsManager.secondPlay) }
        var throughput by rememberSaveable { mutableStateOf(SettingsManager.maxThroughput.toString()) }
        var height by rememberSaveable { mutableStateOf(SettingsManager.videoHeight) }
        var fps by rememberSaveable { mutableStateOf(SettingsManager.videoFps) }
        var band by rememberSaveable { mutableStateOf(SettingsManager.wifiBand.name) }
        val supports5Ghz = remember {
            applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.is5GHzBandSupported != false
        }
        val bandOptions = remember(supports5Ghz) {
            if (supports5Ghz) listOf("Auto", "2.4 GHz", "5 GHz") else listOf("Auto", "2.4 GHz")
        }
        var validationError by rememberSaveable { mutableStateOf<String?>(null) }
        val busy = reset.pending || reset.completedAction != null
        val editable = entryClean && !busy && reset.error == null && exitAction == null
        val systemName = remember { SettingsManager.systemDeviceName() }

        BackHandler { close() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::close, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Close settings and discard unsaved changes" }) {
                        Text("Close")
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Changes apply only when you save. The sink remains stopped when you return.",
                        style = MaterialTheme.typography.bodySmall)
                    SectionTitle("Device identity")
                    SettingsTextField("Device name override", name, { name = it }, editable,
                        supporting = "Empty follows the system device name: $systemName")
                    OutlinedButton(onClick = { name = "" }, enabled = editable) { Text("Use system device name") }
                    SettingsTextField("Manufacturer", manufacturer, { manufacturer = it }, editable)

                    SectionTitle("Video and audio")
                    SettingsDropdown("Resolution", "${height}p", listOf("1080p", "720p"), editable) {
                        height = if (it == "720p") 720 else 1080
                    }
                    SettingsDropdown("FPS", fps.toString(), listOf("60", "30"), editable) { fps = it.toInt() }
                    Text("Only the exact selected resolution and FPS are offered. No SD or lower-rate fallback. Unsupported decoders or senders may fail to connect.",
                        style = MaterialTheme.typography.bodySmall)
                    SettingsToggle("Audio enabled", audio, { audio = it }, editable)
                    SettingsToggle("UIBC touch input", uibc, { uibc = it }, editable)
                    SettingsToggle("Second PLAY compatibility", secondPlay, { secondPlay = it }, editable)
                    SettingsTextField("Maximum throughput (Mbps)", throughput, { throughput = it }, editable,
                        numeric = true, supporting = "1–65535")

                    SectionTitle("Network")
                    SettingsDropdown("Wi-Fi band", bandLabel(band),
                        bandOptions, editable) {
                        band = when (it) {
                            "2.4 GHz" -> P2pBand.GHZ_2_4.name
                            "5 GHz" -> P2pBand.GHZ_5.name
                            else -> P2pBand.AUTO.name
                        }
                    }
                    Text("Session-only channel limit. Forced modes take P2P channel control and return it to Auto when stopped. The sender must support the chosen band.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!supports5Ghz)
                        Text("This device does not support 5 GHz. Choose Auto or 2.4 GHz.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    SettingsTextField("RTSP port", rtsp, { rtsp = it }, editable,
                        numeric = true, supporting = "1–65535")
                    SettingsTextField("RTP video port", rtp, { rtp = it }, editable,
                        numeric = true, supporting = "1–65534; RTCP uses the next port")
                    SettingsTextField("GO intent (client)", client, { client = it }, editable,
                        numeric = true, supporting = "0–15")
                    SettingsTextField("GO intent (fallback)", fallback, { fallback = it }, editable,
                        numeric = true, supporting = "0–15")
                    SettingsToggle("Force autonomous GO", forceGo, { forceGo = it }, editable)
                    SettingsToggle("Persistent listen", persistentListen, { persistentListen = it }, editable)
                    SettingsToggle("Disconnect Wi-Fi in sink mode", disconnectWifi, { disconnectWifi = it }, editable)
                    SettingsTextField("Extended listen (ms)", extListen, { extListen = it }, editable,
                        numeric = true, supporting = "0–5000; 0 keeps the system default")
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = "Settings validation error: $it" }) }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Cleaning up connection…")
                    }
                }
                reset.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { reset.request(exitAction ?: "enter_settings") }, enabled = !busy) {
                        Text("Retry cleanup")
                    }
                }
                Button(enabled = editable, modifier = Modifier.fillMaxWidth(), onClick = {
                    val rtspPort = rtsp.trim().toIntOrNull()
                    val rtpPort = rtp.trim().toIntOrNull()
                    val clientIntent = client.trim().toIntOrNull()
                    val fallbackIntent = fallback.trim().toIntOrNull()
                    val listenMs = extListen.trim().toIntOrNull()
                    val maxThroughput = throughput.trim().toIntOrNull()
                    val error = when {
                        !P2pBand.fromPreference(band).supportedBy(supports5Ghz) ->
                            "5 GHz Wi-Fi is not supported by this device. Choose Auto or 2.4 GHz."
                        rtspPort == null || rtspPort !in 1..65535 -> "RTSP port must be an integer from 1 to 65535."
                        rtpPort == null || rtpPort !in 1..65534 -> "RTP port must be an integer from 1 to 65534 (RTCP uses RTP + 1)."
                        clientIntent == null || clientIntent !in 0..15 -> "Client GO intent must be an integer from 0 to 15."
                        fallbackIntent == null || fallbackIntent !in 0..15 -> "Fallback GO intent must be an integer from 0 to 15."
                        listenMs == null || listenMs !in 0..5000 -> "Extended listen must be an integer from 0 to 5000 ms."
                        maxThroughput == null || maxThroughput !in 1..65535 -> "Maximum throughput must be an integer from 1 to 65535 Mbps."
                        else -> null
                    }
                    validationError = error
                    if (error == null) {
                        SettingsManager.deviceName = name
                        SettingsManager.manufacturer = manufacturer.trim()
                        SettingsManager.rtspPort = rtspPort!!
                        SettingsManager.rtpVideoPort = rtpPort!!
                        SettingsManager.goIntentClient = clientIntent!!
                        SettingsManager.goIntentFallback = fallbackIntent!!
                        SettingsManager.forceAutonomousGo = forceGo
                        SettingsManager.usePersistentListen = persistentListen
                        SettingsManager.disconnectWifiInSinkMode = disconnectWifi
                        SettingsManager.extListenMs = listenMs!!
                        SettingsManager.audioEnabled = audio
                        SettingsManager.uibcEnabled = uibc
                        SettingsManager.secondPlay = secondPlay
                        SettingsManager.maxThroughput = maxThroughput!!
                        SettingsManager.videoHeight = height
                        SettingsManager.videoFps = fps
                        SettingsManager.wifiBand = P2pBand.fromPreference(band)
                        SettingsManager.logAppliedSettings()
                        // Retained and saved separately from the draft: a restored Save only
                        // retries cleanup, never rewrites settings or navigates a second time.
                        exitAction = "save"
                        reset.request("save")
                    }
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    numeric: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        supportingText = { if (supporting != null) Text(supporting) })
}

@Composable
private fun SettingsToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value. Open options" }) {
                Text(value, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        onSelect(option)
                        expanded = false
                    })
                }
            }
        }
    }
}

private fun bandLabel(band: String): String = when (P2pBand.fromPreference(band)) {
    P2pBand.AUTO -> "Auto"
    P2pBand.GHZ_2_4 -> "2.4 GHz"
    P2pBand.GHZ_5 -> "5 GHz"
}
