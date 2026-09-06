package foxlost.miracast.sink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_ACTIVITY_LOG_VISIBLE_ROWS = 4

class MainActivity : ComponentActivity() {
    private val TAG = "MiracastApp"
    private var isServiceRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        title = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        }

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            Log.d(TAG, "Permissions granted: $allGranted")
        }

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            val ungranted = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (ungranted.isNotEmpty()) {
                requestPermissionLauncher.launch(ungranted.toTypedArray())
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    Dashboard()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning = MiracastService.isActive
    }

    @Composable
    private fun Dashboard() {
        // Local state mirrors of the persisted settings.
        var deviceName by remember { mutableStateOf(SettingsManager.deviceName) }
        var manufacturer by remember { mutableStateOf(SettingsManager.manufacturer) }
        var rtspPort by remember { mutableStateOf(SettingsManager.rtspPort.toString()) }
        var rtpPort by remember { mutableStateOf(SettingsManager.rtpVideoPort.toString()) }
        var goClient by remember { mutableStateOf(SettingsManager.goIntentClient.toString()) }
        var goFallback by remember { mutableStateOf(SettingsManager.goIntentFallback.toString()) }
        var maxTput by remember { mutableStateOf(SettingsManager.maxThroughput.toString()) }
        var forceGo by remember { mutableStateOf(SettingsManager.forceAutonomousGo) }
        var persistentListen by remember { mutableStateOf(SettingsManager.usePersistentListen) }
        var audioEnabled by remember { mutableStateOf(SettingsManager.audioEnabled) }
        var uibcEnabled by remember { mutableStateOf(SettingsManager.uibcEnabled) }
        var secondPlay by remember { mutableStateOf(SettingsManager.secondPlay) }
        var videoProfile by remember { mutableStateOf(SettingsManager.videoProfile) }
        var showSettings by remember { mutableStateOf(false) }
        val activityLogEvents by DebugEventLog.events.collectAsState()
        val visibleActivityLogEvents = remember(activityLogEvents) {
            activityLogEvents
                .takeLast(DebugEventLog.MAX_EVENTS)
                .asReversed()
                .take(MAX_ACTIVITY_LOG_VISIBLE_ROWS)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Miracast Sink", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Universal Android Receiver", fontSize = 14.sp, color = Color(0xFF94A3B8))
                Text("Made with Free Time and Free Will by FoxLost", fontSize = 11.sp, color = Color(0xFF64748B))

                Spacer(modifier = Modifier.height(24.dp))

                // ---- Status card ----
                val statusLabel = if (!isServiceRunning) "Inactive" else "Ready to accept"
                val statusColor = if (!isServiceRunning) Color(0xFFF87171) else Color(0xFF4ADE80)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Status: $statusLabel", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = statusColor)
                        if (isServiceRunning) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Connect to: $deviceName",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF93C5FD),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Open Cast/Wireless Display on your source device\nand select this device from the list",
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ---- Start / Stop ----
                Button(
                    onClick = {
                        if (isServiceRunning) { isServiceRunning = false; stopService() }
                        else { isServiceRunning = true; startService() }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) Color(0xFFDC2626) else Color(0xFF2563EB)
                    )
                ) {
                    Text(
                        if (isServiceRunning) "STOP MIRACAST SINK" else "START MIRACAST SINK",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Settings toggle ----
                OutlinedButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (showSettings) "HIDE SETTINGS" else "SETTINGS", color = Color(0xFF93C5FD))
                }

                if (showSettings) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                            Text("Device", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsTextField(
                                label = "Device name (P2P / WFD)",
                                value = deviceName,
                                onChange = {
                                    deviceName = it
                                    SettingsManager.deviceName = it
                                }
                            )
                            Text(
                                "Default: system name \"${SettingsManager.systemDeviceName()}\"",
                                fontSize = 11.sp, color = Color(0xFF64748B)
                            )
                            TextButton(onClick = {
                                deviceName = SettingsManager.systemDeviceName()
                                SettingsManager.deviceName = ""
                            }) { Text("Reset to system name", color = Color(0xFF93C5FD), fontSize = 12.sp) }

                            SettingsTextField(
                                label = "Manufacturer",
                                value = manufacturer,
                                onChange = { manufacturer = it; SettingsManager.manufacturer = it }
                            )

                            Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))
                            Text("Network", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) {
                                    SettingsNumberField("RTSP port", rtspPort) {
                                        rtspPort = it; it.toIntOrNull()?.let { v -> SettingsManager.rtspPort = v }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    SettingsNumberField("RTP video port", rtpPort) {
                                        rtpPort = it; it.toIntOrNull()?.let { v -> SettingsManager.rtpVideoPort = v }
                                    }
                                }
                            }

                            Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))
                            Text("P2P / GO Negotiation", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) {
                                    SettingsNumberField("GO intent (client)", goClient) {
                                        goClient = it; it.toIntOrNull()?.let { v -> SettingsManager.goIntentClient = v }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    SettingsNumberField("GO intent (fallback)", goFallback) {
                                        goFallback = it; it.toIntOrNull()?.let { v -> SettingsManager.goIntentFallback = v }
                                    }
                                }
                            }
                            Text(
                                "0 = let source be GO (Windows). Retry escalates to fallback.",
                                fontSize = 11.sp, color = Color(0xFF64748B)
                            )
                            SettingsSwitch("Force sink as Group Owner", forceGo) {
                                forceGo = it; SettingsManager.forceAutonomousGo = it
                            }
                            Text(
                                "Enable only for sources that need the sink to be GO (some Android).",
                                fontSize = 11.sp, color = Color(0xFF64748B)
                            )
                            SettingsSwitch("Persistent listen (required for Windows)", persistentListen) {
                                persistentListen = it; SettingsManager.usePersistentListen = it
                            }
                            Text(
                                "Keeps the radio in LISTEN state so Windows GO negotiation is accepted.",
                                fontSize = 11.sp, color = Color(0xFF64748B)
                            )

                            Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))
                            Text("Media", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Max video resolution", fontSize = 13.sp, color = Color(0xFF94A3B8))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProfileChip("1080p60", videoProfile == SettingsManager.PROFILE_FULL) {
                                    videoProfile = SettingsManager.PROFILE_FULL; SettingsManager.videoProfile = it
                                }
                                ProfileChip("1080p30", videoProfile == SettingsManager.PROFILE_1080P30) {
                                    videoProfile = SettingsManager.PROFILE_1080P30; SettingsManager.videoProfile = it
                                }
                                ProfileChip("720p60", videoProfile == SettingsManager.PROFILE_720P60) {
                                    videoProfile = SettingsManager.PROFILE_720P60; SettingsManager.videoProfile = it
                                }
                            }
                            SettingsSwitch("Audio (LPCM)", audioEnabled) {
                                audioEnabled = it; SettingsManager.audioEnabled = it
                            }
                            SettingsSwitch("UIBC (touch back-channel)", uibcEnabled) {
                                uibcEnabled = it; SettingsManager.uibcEnabled = it
                            }
                            SettingsSwitch("Send second PLAY", secondPlay) {
                                secondPlay = it; SettingsManager.secondPlay = it
                            }
                            SettingsNumberField("WFD max throughput (Mbps)", maxTput) {
                                maxTput = it; it.toIntOrNull()?.let { v -> SettingsManager.maxThroughput = v }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            ActivityLogCard(entries = visibleActivityLogEvents)
        }
    }

    @Composable
    private fun ActivityLogCard(entries: List<DebugEvent>) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 156.dp, max = 180.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Activity", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("last ${DebugEventLog.MAX_EVENTS}", fontSize = 11.sp, color = Color(0xFF64748B))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "category / message",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "No activity yet. Telemetry will appear here once events are emitted.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entries.forEach { entry ->
                            ActivityLogRow(entry)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActivityLogRow(entry: DebugEvent) {
        val isError = entry.category == DebugEventCategory.ERROR
        val rowBackground = if (isError) Color(0xFF3F1D2C) else Color(0xFF0F172A)
        val categoryColor = if (isError) Color(0xFFFCA5A5) else Color(0xFF93C5FD)
        val messageColor = if (isError) Color(0xFFFECACA) else Color(0xFFE2E8F0)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(rowBackground)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                entry.category.name,
                modifier = Modifier.width(56.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = categoryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.message,
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
                color = messageColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun SettingsTextField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true,
            colors = textFieldColors()
        )
    }

    @Composable
    private fun SettingsNumberField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) onChange(it) },
            label = { Text(label, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = textFieldColors()
        )
    }

    @Composable
    private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = Color.White)
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2563EB))
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RowScope.ProfileChip(label: String, selected: Boolean, onSelect: (Int) -> Unit) {
        val profileValue = when (label) {
            "1080p60" -> SettingsManager.PROFILE_FULL
            "1080p30" -> SettingsManager.PROFILE_1080P30
            else -> SettingsManager.PROFILE_720P60
        }
        FilterChip(
            selected = selected,
            onClick = { onSelect(profileValue) },
            label = { Text(label, fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF2563EB),
                selectedLabelColor = Color.White
            )
        )
    }

    @Composable
    private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF2563EB),
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = Color(0xFF93C5FD),
        unfocusedLabelColor = Color(0xFF64748B)
    )

    private fun startService() {
        val intent = Intent(this, MiracastService::class.java).apply {
            action = MiracastService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopService() {
        val intent = Intent(this, MiracastService::class.java).apply {
            action = MiracastService.ACTION_STOP
        }
        startForegroundService(intent)
    }
}
