package foxlost.miracast.sink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val TAG = "MiracastApp"
    private var isServiceRunning by mutableStateOf(false)
    private var sinkDeviceName by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        }

        sinkDeviceName = getLocalDeviceName()

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            Log.d(TAG, "Permissions granted: $allGranted")
        }

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Miracast Sink",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Universal Android Receiver",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "Made with Free Time and Free Will by FoxLost",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

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
                                Text(
                                    text = "Status: $statusLabel",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor
                                )
                                if (isServiceRunning) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Connect to: $sinkDeviceName",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF93C5FD),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Open Cast/Wireless Display on your source device\nand select this device from the list",
                                        fontSize = 13.sp,
                                        color = Color(0xFF64748B),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = {
                                if (isServiceRunning) {
                                    isServiceRunning = false; stopService()
                                } else {
                                    isServiceRunning = true; startService()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceRunning) Color(0xFFDC2626) else Color(0xFF2563EB)
                            )
                        ) {
                            Text(
                                text = if (isServiceRunning) "STOP MIRACAST SINK" else "START MIRACAST SINK",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning = MiracastService.isActive
    }

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

    private fun getLocalDeviceName(): String {
        try {
            val dump = Runtime.getRuntime().exec(arrayOf("dumpsys", "wifi")).inputStream.bufferedReader().readText()
            Regex("wifi_p2p_device_name=([^\n\r]+)").find(dump)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotBlank()) return name
            }
        } catch (_: Exception) {}
        return Build.MODEL
    }
}
