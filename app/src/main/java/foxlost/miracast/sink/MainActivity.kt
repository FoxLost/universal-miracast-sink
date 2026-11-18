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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private var deviceName by mutableStateOf("")
    private lateinit var reset: NavigationReset

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        reset = ViewModelProvider(this)[NavigationReset::class.java]
        if (!reset.pending && reset.completedAction == null) {
            savedInstanceState?.getString("navigation_action")?.let(reset::request)
        }
        title = getString(R.string.app_name)
        setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Log.d("MiracastApp", "Permissions granted: ${it.values.all { granted -> granted }}")
        }
        val permissions = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ungranted = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) permissionsLauncher.launch(ungranted.toTypedArray())
        setContent {
            MaterialTheme {
                ResetCompletion(reset) { action ->
                    if (action == "settings") startActivity(Intent(this, SettingsActivity::class.java))
                }
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0F172A)) { Dashboard() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        deviceName = SettingsManager.deviceName
        val generation = intent.getLongExtra(MiracastService.EXTRA_SESSION_GENERATION, -1L)
        intent.removeExtra(MiracastService.EXTRA_SESSION_GENERATION)
        if (generation >= 0L) MiracastService.returnToPlayer(this, generation)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        reset.requestedAction?.let { outState.putString("navigation_action", it) }
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun Dashboard() {
        val isServiceRunning by MiracastService.activeState.collectAsState()
        val playerSession by MiracastService.playerSession.collectAsState()
        val busy = reset.pending || reset.completedAction != null
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Miracast Sink", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Universal Android Receiver", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
                IconButton(onClick = { reset.request("settings") }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Open settings", tint = Color(0xFF93C5FD))
                }
            }
            Column(Modifier.fillMaxWidth().weight(0.9f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val status = when {
                            busy -> "Cleaning up…"
                            reset.error != null -> "Cleanup not confirmed"
                            playerSession != null -> "Miracast session active"
                            isServiceRunning -> "Ready to accept"
                            else -> "Inactive"
                        }
                        Text("Status: $status", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                            color = if (isServiceRunning && !busy && reset.error == null) Color(0xFF4ADE80) else Color(0xFFFBBF24))
                        if (isServiceRunning && playerSession == null && !busy && reset.error == null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Connect to: $deviceName", color = Color(0xFF93C5FD), textAlign = TextAlign.Center)
                            Text("Open Cast / Wireless Display on your source device", fontSize = 12.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
                        }
                    }
                }
                Button(
                    onClick = {
                        if (isServiceRunning) reset.request("stop")
                        else {
                            try {
                                startForegroundService(Intent(this@MainActivity, MiracastService::class.java).setAction(MiracastService.ACTION_START))
                            } catch (e: Exception) {
                                Log.e("MiracastApp", "Unable to start sink", e)
                            }
                        }
                    },
                    enabled = !busy && reset.error == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isServiceRunning) Color(0xFFDC2626) else Color(0xFF2563EB)),
                ) { Text(if (isServiceRunning) "STOP MIRACAST SINK" else "START MIRACAST SINK", fontWeight = FontWeight.Bold) }
                playerSession?.let { session ->
                    OutlinedButton(
                        onClick = { MiracastService.returnToPlayer(this@MainActivity, session.generation) },
                        enabled = !busy && reset.error == null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Return to Miracast session", color = Color(0xFF93C5FD)) }
                }
                reset.error?.let { error ->
                    Text(error, color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    OutlinedButton(onClick = { reset.request(reset.requestedAction ?: "stop") }, enabled = !busy) {
                        Text("Retry cleanup", color = Color(0xFF93C5FD))
                    }
                }
                Text("Made with Free Time and Free Will by FoxLost", fontSize = 11.sp, color = Color(0xFF64748B))
            }
            ActivityLogCard(Modifier.fillMaxWidth().weight(1.25f))
        }
    }

    @Composable
    private fun ActivityLogCard(modifier: Modifier) {
        // Only the bounded, independently scrollable panel observes event changes.
        val events by DebugEventLog.events.collectAsState()
        val entries = remember(events) { events.asReversed() }
        Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Activity", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("last ${DebugEventLog.MAX_EVENTS}", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
                Spacer(Modifier.height(10.dp))
                if (entries.isEmpty()) Text("No activity yet. Start the sink to see connection events.", fontSize = 13.sp, color = Color(0xFF94A3B8))
                else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { entry ->
                        val error = entry.category == DebugEventCategory.ERROR
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (error) Color(0xFF3F1D2C) else Color(0xFF0F172A)).padding(10.dp)) {
                            Text(entry.category.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = if (error) Color(0xFFFCA5A5) else Color(0xFF93C5FD))
                            Text(entry.message, fontSize = 13.sp, color = Color(0xFFE2E8F0))
                        }
                    }
                }
            }
        }
    }
}
