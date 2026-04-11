package com.signal_sense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.signal_sense.core.SignalAlert
import com.signal_sense.core.SignalData
import com.signal_sense.core.SignalMonitor
import com.signal_sense.core.SignalMonitorService
import com.signal_sense.core.SignalZone
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var monitor: SignalMonitor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startMonitoring()
        } else {
            Toast.makeText(this, "Permissions denied!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = SignalMonitor(applicationContext)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startMonitoring()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startMonitoring() {
        startForegroundService(Intent(this, SignalMonitorService::class.java))
        monitor.start()

        var signalData = SignalData()
        var alertText = ""

        setContent {
            var data by remember { mutableStateOf(SignalData()) }
            var alert by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                launch {
                    monitor.signalFlow.collectLatest { data = it }
                }
                launch {
                    monitor.alertFlow.collectLatest { a ->
                        a?.let { alert = "${it.title}: ${it.message}" }
                    }
                }
            }

            SignalSenseUI(data = data, alertText = alert)
        }
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }
}

@Composable
fun SignalSenseUI(data: SignalData, alertText: String) {
    val zoneColor = when (data.zone) {
        SignalZone.STRONG  -> Color(0xFF4CAF50)
        SignalZone.WEAK    -> Color(0xFFFFC107)
        SignalZone.DEAD    -> Color(0xFFF44336)
        SignalZone.UNKNOWN -> Color(0xFF9E9E9E)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SignalSense",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(160.dp)
                .background(zoneColor, RoundedCornerShape(80.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = data.zone.emoji,
                    fontSize = 36.sp
                )
                Text(
                    text = data.zone.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricCard(label = "dBm",     value = "${data.dbm}")
            MetricCard(label = "SINR",    value = if (data.sinr.isNaN()) "N/A" else "${"%.1f".format(data.sinr)}")
            MetricCard(label = "RSRQ",    value = if (data.rsrq.isNaN()) "N/A" else "${"%.1f".format(data.rsrq)}")
            MetricCard(label = "Network", value = data.networkLabel)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (alertText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF44336)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = alertText,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Polling every 5 seconds",
            fontSize = 12.sp,
            color = Color(0xFF9E9E9E)
        )
    }
}

@Composable
fun MetricCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
        }
    }
}