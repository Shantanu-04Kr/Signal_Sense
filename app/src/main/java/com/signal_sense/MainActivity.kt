package com.signal_sense

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.signal_sense.core.SignalAlert
import com.signal_sense.core.SignalData
import com.signal_sense.core.SignalMonitor
import com.signal_sense.core.SignalMonitorService
import com.signal_sense.core.SignalZone
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── Colors ────────────────────────────────────────────────────────────────────
val BgDark       = Color(0xFF020617)
val BgCard       = Color(0xFF0F172A)
val BgCardDeep   = Color(0xFF0E1A2B)
val Accent       = Color(0xFF22D3EE)   // cyan
val ColorStrong  = Color(0xFF22C55E)   // green
val ColorWeak    = Color(0xFFEAB308)   // yellow
val ColorDead    = Color(0xFFEF4444)   // red
val ColorUnknown = Color(0xFF94A3B8)   // slate
val TextMuted    = Color(0xFF94A3B8)
val TextDim      = Color(0xFF475569)
val TextBody     = Color(0xFFCBD5E1)
val Divider      = Color(0xFF1E293B)

// ── Helpers ───────────────────────────────────────────────────────────────────
fun signalColor(zone: SignalZone): Color = when (zone) {
    SignalZone.STRONG  -> ColorStrong
    SignalZone.WEAK    -> ColorWeak
    SignalZone.DEAD    -> ColorDead
    SignalZone.UNKNOWN -> ColorUnknown
}

fun barColor(pct: Int): Color = when {
    pct <= 30 -> ColorDead
    pct <= 70 -> ColorWeak
    else      -> ColorStrong
}

// Derive zone + color from average of calls/payments/data
fun avgZone(calls: Int, payments: Int, data: Int): Pair<SignalZone, Color> {
    val avg = (calls + payments + data) / 3
    return when {
        avg <= 30 -> Pair(SignalZone.DEAD,   ColorDead)
        avg <= 70 -> Pair(SignalZone.WEAK,   ColorWeak)
        else      -> Pair(SignalZone.STRONG, ColorStrong)
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private lateinit var monitor: SignalMonitor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startMonitoring()
        else Toast.makeText(this, "Permissions denied!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = SignalMonitor(applicationContext)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startMonitoring()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startMonitoring() {
        startForegroundService(Intent(this, SignalMonitorService::class.java))
        monitor.start()

        setContent {
            var data      by remember { mutableStateOf(SignalData()) }
            var aiText    by remember { mutableStateOf("Analyzing signal...") }
            var connTitle by remember { mutableStateOf("Checking...") }
            var connDesc  by remember { mutableStateOf("Testing connectivity") }

            LaunchedEffect(Unit) {
                launch { monitor.signalFlow.collectLatest    { data = it } }
                launch { monitor.connectivityDesc.collectLatest { (t, d) -> connTitle = t; connDesc = d } }
                launch {
                    monitor.alertFlow.collectLatest { a ->
                        aiText = if (a != null) {
                            "${a.title}: ${a.message}"
                        } else {
                            when (data.zone) {
                                SignalZone.STRONG  -> "Signal is excellent. Stay here for calls, payments, and streaming."
                                SignalZone.WEAK    -> "Signal is weak. Move toward an open area to improve signal."
                                SignalZone.DEAD    -> "Dead zone. Move at least 50 metres toward the main road."
                                SignalZone.UNKNOWN -> "Analyzing signal strength..."
                            }
                        }
                    }
                }

                     else -> "Monitoring signal..."
                        }
                    }
                }
            }

            SignalSenseUI(
                data           = data,
                aiText         = aiText,
                onHeatmapClick = {
                    startActivity(Intent(this@MainActivity, HeatmapActivity::class.java))
                }
            )
        }
    }

    override fun onDestroy() { monitor.stop(); super.onDestroy() }
}

// ── Main UI ───────────────────────────────────────────────────────────────────
@Composable
fun SignalSenseUI(
    data: SignalData,
    aiText: String,
    onHeatmapClick: () -> Unit = {}
) {
    // Derive circle color from avg of calls/payments/data if available
    val calls    = data.callQuality    // Int 0–100 (add to SignalData if needed)
    val payments = data.paymentQuality // Int 0–100
    val dataQ    = data.dataQuality    // Int 0–100
    val (_, circleColor) = avgZone(calls, payments, dataQ)
    val zoneColor = signalColor(data.zone)

    var showAdvanced by remember { mutableStateOf(false) }

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "p1"
    )
    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1800, 600, easing = LinearEasing), RepeatMode.Restart),
        label = "p2"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Title ─────────────────────────────────────────────────────────
            Text(
                text = "SignalSense",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 4.sp
            )
            Text(
                text = "AI SIGNAL ANALYZER",
                fontSize = 10.sp,
                color = TextDim,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 26.dp)
            )

            // ── Signal Circle ─────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .padding(bottom = 0.dp)
            ) {
                // Pulse ring 1
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulse1)
                        .clip(CircleShape)
                        .border(2.dp, circleColor.copy(alpha = (1f - (pulse1 - 1f) / 0.6f).coerceIn(0f,1f)), CircleShape)
                )
                // Pulse ring 2
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulse2)
                        .clip(CircleShape)
                        .border(2.dp, circleColor.copy(alpha = (1f - (pulse2 - 1f) / 0.6f).coerceIn(0f,1f)), CircleShape)
                )
                // Main filled circle
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(144.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(circleColor, circleColor.copy(alpha = 0.6f))
                            )
                        )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SIGNAL",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = data.zone.label,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Connectivity Card ─────────────────────────────────────────────
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("CONNECTIVITY", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(14.dp))
                MetricBar(label = "📞  Calls",    pct = calls)
                MetricBar(label = "💳  Payments", pct = payments)
                MetricBar(label = "📡  Data",     pct = dataQ)
            }

            Spacer(Modifier.height(14.dp))

            // ── AI Prediction Card ────────────────────────────────────────────
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("AI PREDICTION", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(text = aiText, color = TextBody, fontSize = 14.sp, lineHeight = 22.sp)
            }

            Spacer(Modifier.height(14.dp))

            // ── Advanced Metrics Button ───────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = true },
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📊", fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("ADVANCED METRICS", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Text("›", color = TextDim, fontSize = 22.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Heatmap Button ────────────────────────────────────────────────
            Button(
                onClick = onHeatmapClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF0891B2), Color(0xFF0E7490))),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🗺️   SIGNAL HEATMAP",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }
            }
        }

        // ── Advanced Metrics Modal ─────────────────────────────────────────────
        if (showAdvanced) {
            AdvancedMetricsModal(
                data       = data,
                sigColor   = circleColor,
                onDismiss  = { showAdvanced = false }
            )
        }
    }
}

// ── Section Card wrapper ──────────────────────────────────────────────────────
@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.13f))
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

// ── Metric Bar ────────────────────────────────────────────────────────────────
@Composable
fun MetricBar(label: String, pct: Int) {
    val color = barColor(pct)
    val animPct by animateFloatAsState(
        targetValue = pct / 100f,
        animationSpec = tween(1000),
        label = "bar"
    )
    Column(modifier = Modifier.padding(bottom = 11.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextMuted, fontSize = 13.sp)
            Text("$pct%", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Divider)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animPct)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.5f), color)
                        )
                    )
            )
        }
    }
}

// ── Advanced Metrics Modal ────────────────────────────────────────────────────
@Composable
fun AdvancedMetricsModal(
    data: SignalData,
    sigColor: Color,
    onDismiss: () -> Unit
) {
    val rows = listOf(
        Triple("Signal Strength", "dBm",     "${data.dbm}"),
        Triple("Signal Quality",  "SINR",    if (data.sinr.isNaN()) "N/A" else "${"%.1f".format(data.sinr)}"),
        Triple("Stability Index", "RSRQ",    if (data.rsrq.isNaN()) "N/A" else "${"%.1f".format(data.rsrq)}"),
        Triple("Network Type",    "",        data.networkLabel),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable { onDismiss() }
            )
            // Sheet
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(BgCard)
                    .padding(horizontal = 22.dp, vertical = 24.dp)
            ) {
                // Handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(TextDim)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(20.dp))

                // Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📊", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("ADVANCED METRICS", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }

                Spacer(Modifier.height(18.dp))

                // Rows
                rows.forEachIndexed { i, (label, unit, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(label, color = TextBody, fontSize = 14.sp)
                            if (unit.isNotEmpty())
                                Text(unit, color = TextDim, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                        // Network type uses Accent, others use signal color
                        val valColor = if (i == 3) Accent else sigColor
                        Text(value, color = valColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    if (i < rows.lastIndex)
                        HorizontalDivider(color = Divider, thickness = 1.dp)
                }

                Spacer(Modifier.height(22.dp))

                // Close button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Accent.copy(alpha = 0.3f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                ) {
                    Text("CLOSE", letterSpacing = 2.sp, fontSize = 12.sp)
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
