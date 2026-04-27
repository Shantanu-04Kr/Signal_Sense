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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.signal_sense.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── Colors ────────────────────────────────────────────────────────────────────
private val BG_COLOR      = Color(0xFF020617)
private val SURFACE_COLOR = Color(0xFF0F172A)
private val ACCENT_COLOR  = Color(0xFF22D3EE)
private val SLATE_COLOR   = Color(0xFF94A3B8)
private val SLATE2_COLOR  = Color(0xFF475569)
private val SLATE3_COLOR  = Color(0xFF1E293B)
private val TEXT_COLOR    = Color(0xFFCBD5E1)
private val DARK_COLOR    = Color(0xFF334155)

private fun pctColor(pct: Int): Color = when {
    pct <= 30 -> Color(0xFFEF4444)
    pct <= 70 -> Color(0xFFEAB308)
    else      -> Color(0xFF22C55E)
}

private fun pctGlow(pct: Int): Color = when {
    pct <= 30 -> Color(0xFFB91C1C)
    pct <= 70 -> Color(0xFFCA8A04)
    else      -> Color(0xFF16A34A)
}

private fun pctLabel(pct: Int): String = when {
    pct <= 30 -> "DEAD"
    pct <= 70 -> "WEAK"
    else      -> "STRONG"
}

// ── Activity ──────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private lateinit var monitor: SignalMonitor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val phoneGranted    = results[Manifest.permission.READ_PHONE_STATE] ?: false
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: results[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (phoneGranted && locationGranted) startMonitoring()
        else {
            val denied = results.filter { !it.value }.keys.joinToString()
            Toast.makeText(this, "Denied: $denied", Toast.LENGTH_LONG).show()
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
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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

        setContent {
            var data      by remember { mutableStateOf(SignalData()) }
            var alertText by remember { mutableStateOf("") }
            var callsPct  by remember { mutableStateOf(50) }
            var payPct    by remember { mutableStateOf(50) }
            var dataPct   by remember { mutableStateOf(50) }
            var aiText    by remember { mutableStateOf("Analyzing signal…") }

            LaunchedEffect(Unit) {
                launch {
                    monitor.signalFlow.collectLatest { d ->
                        data = d
                        when (d.zone) {
                            SignalZone.STRONG -> {
                                callsPct = (85..98).random()
                                payPct   = (82..95).random()
                                dataPct  = (88..99).random()
                                aiText   = "Signal is excellent. You're in the best spot — stay here for calls, payments, and streaming."
                            }
                            SignalZone.WEAK -> {
                                callsPct = (40..65).random()
                                payPct   = (38..62).random()
                                dataPct  = (45..70).random()
                                aiText   = "Signal is weak. Move 20 metres toward an open area to improve signal by ~40%."
                            }
                            SignalZone.DEAD -> {
                                callsPct = (5..20).random()
                                payPct   = (3..15).random()
                                dataPct  = (5..18).random()
                                aiText   = "Dead zone detected. Move at least 50 metres toward the main road for usable signal."
                            }
                            SignalZone.UNKNOWN -> {
                                callsPct = 50; payPct = 50; dataPct = 50
                                aiText   = "Analyzing signal…"
                            }
                        }
                    }
                }
                launch {
                    monitor.alertFlow.collectLatest { a ->
                        alertText = when (a) {
                            is SignalAlert.SignalRestored -> ""
                            null -> ""
                            else -> "${a.title}: ${a.message}"
                        }
                    }
                }
            }

            SignalSenseUI(
                data           = data,
                alertText      = alertText,
                callsPct       = callsPct,
                payPct         = payPct,
                dataPct        = dataPct,
                aiText         = aiText,
                onHeatmapClick = {
                    startActivity(Intent(this@MainActivity, HeatmapActivity::class.java))
                }
            )
        }
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }
}

// ── Main Screen ───────────────────────────────────────────────────────────────
@Composable
fun SignalSenseUI(
    data: SignalData,
    alertText: String,
    callsPct: Int,
    payPct: Int,
    dataPct: Int,
    aiText: String,
    onHeatmapClick: () -> Unit = {}
) {
    val avg          = (callsPct + payPct + dataPct) / 3
    val circleColor  = pctColor(avg)
    val glowColor    = pctGlow(avg)
    val circleLabel  = pctLabel(avg)

    // Pulse flash every 5 seconds
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            pulse = true
            delay(600)
            pulse = false
        }
    }

    // Ring 1 animation
    val infiniteRing1 = rememberInfiniteTransition(label = "ring1")
    val ring1Scale by infiniteRing1.animateFloat(
        initialValue = 1f,
        targetValue  = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1scale"
    )
    val ring1Alpha by infiniteRing1.animateFloat(
        initialValue = 0.6f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1alpha"
    )

    // Ring 2 animation — delayed
    val infiniteRing2 = rememberInfiniteTransition(label = "ring2")
    val ring2Scale by infiniteRing2.animateFloat(
        initialValue = 1f,
        targetValue  = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing, delayMillis = 650),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2scale"
    )
    val ring2Alpha by infiniteRing2.animateFloat(
        initialValue = 0.6f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing, delayMillis = 650),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2alpha"
    )

    // Animated bars
    val callsAnim by animateIntAsState(targetValue = callsPct, animationSpec = tween(1000), label = "calls")
    val payAnim   by animateIntAsState(targetValue = payPct,   animationSpec = tween(1000), label = "pay")
    val dataAnim  by animateIntAsState(targetValue = dataPct,  animationSpec = tween(1000), label = "data")

    var showModal by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG_COLOR)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Title ─────────────────────────────────────────────────────────
            Text(
                text = "SignalSense",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = ACCENT_COLOR
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AI SIGNAL ANALYZER",
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                color = DARK_COLOR
            )

            Spacer(Modifier.height(24.dp))

            // ── Signal Circle with two pulsing rings ──────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(190.dp)
            ) {
                // Ring 1
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .drawBehind {
                            drawCircle(
                                color  = circleColor.copy(alpha = ring1Alpha),
                                radius = (size.minDimension / 2f) * ring1Scale,
                                style  = Stroke(width = 2.dp.toPx())
                            )
                        }
                )
                // Ring 2
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .drawBehind {
                            drawCircle(
                                color  = circleColor.copy(alpha = ring2Alpha),
                                radius = (size.minDimension / 2f) * ring2Scale,
                                style  = Stroke(width = 2.dp.toPx())
                            )
                        }
                )
                // Main filled circle
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(circleColor, glowColor)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text  = "SIGNAL",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = circleLabel,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                }
                // Pulse flash overlay
                if (pulse) {
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Connectivity Bars ─────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = SURFACE_COLOR),
                shape    = RoundedCornerShape(16.dp),
                border   = BorderStroke(1.dp, ACCENT_COLOR.copy(alpha = 0.13f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ACCENT_COLOR)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "CONNECTIVITY",
                            color = ACCENT_COLOR,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    MetricBarRow(label = "📞  Calls",    pct = callsAnim)
                    MetricBarRow(label = "💳  Payments", pct = payAnim)
                    MetricBarRow(label = "📡  Data",     pct = dataAnim)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── AI Prediction ─────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = SURFACE_COLOR),
                shape    = RoundedCornerShape(16.dp),
                border   = BorderStroke(1.dp, ACCENT_COLOR.copy(alpha = 0.13f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Text(text = "🤖", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AI PREDICTION",
                            color = ACCENT_COLOR,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    Text(
                        text       = aiText,
                        color      = TEXT_COLOR,
                        fontSize   = 14.sp,
                        lineHeight = 24.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Advanced Metrics Button ────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showModal = true },
                colors = CardDefaults.cardColors(containerColor = SURFACE_COLOR),
                shape  = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ACCENT_COLOR.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "📊", fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "ADVANCED METRICS",
                            color = ACCENT_COLOR,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    Text(
                        text     = "›",
                        color    = SLATE2_COLOR,
                        fontSize = 22.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Heatmap Button ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0891B2), Color(0xFF0E7490))
                        )
                    )
                    .clickable { onHeatmapClick() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(text = "🗺️", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "SIGNAL HEATMAP",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp
                    )
                }
            }

            // ── Alert box ─────────────────────────────────────────────────────
            if (alertText.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFFEF4444)),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text     = alertText,
                        color    = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text     = "Polling every 5 seconds",
                fontSize = 10.sp,
                color    = SLATE2_COLOR
            )
        }

        // ── Advanced Metrics Modal ─────────────────────────────────────────────
        if (showModal) {
            AdvancedMetricsModal(
                data      = data,
                sigColor  = circleColor,
                onDismiss = { showModal = false }
            )
        }
    }
}

// ── Metric Bar Row ────────────────────────────────────────────────────────────
@Composable
fun MetricBarRow(label: String, pct: Int) {
    val color = pctColor(pct)
    Column(modifier = Modifier.padding(bottom = 11.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text      = label,
                color     = SLATE_COLOR,
                fontSize  = 13.sp,
                letterSpacing = 1.sp
            )
            Text(
                text       = "$pct%",
                color      = color,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(SLATE3_COLOR)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(color.copy(alpha = 0.53f), color)
                        )
                    )
            )
        }
    }
}

// ── Advanced Metrics Modal (bottom sheet style) ────────────────────────────────
@Composable
fun AdvancedMetricsModal(
    data: SignalData,
    sigColor: Color,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.73f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { /* consume — don't dismiss */ },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape  = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, ACCENT_COLOR.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 22.dp, end = 22.dp, top = 24.dp, bottom = 36.dp
                )
            ) {
                // Handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0xFF334155))
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(20.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "📊", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ADVANCED METRICS",
                        color = ACCENT_COLOR,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.height(18.dp))

                val rows = listOf(
                    Triple("Signal Strength", "dBm",  "${data.dbm}"),
                    Triple("Signal Quality",  "SINR",
                        if (data.sinr.isNaN()) "N/A" else "${"%.1f".format(data.sinr)}"),
                    Triple("Stability Index", "RSRQ",
                        if (data.rsrq.isNaN()) "N/A" else "${"%.1f".format(data.rsrq)}"),
                    Triple("Network Type",    "",     data.networkLabel),
                )

                rows.forEachIndexed { index, (label, unit, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text     = label,
                                color    = TEXT_COLOR,
                                fontSize = 14.sp
                            )
                            if (unit.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text          = unit,
                                    color         = SLATE2_COLOR,
                                    fontSize      = 10.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                        Text(
                            text       = value,
                            color      = if (unit.isEmpty()) ACCENT_COLOR else sigColor,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    if (index < rows.size - 1) {
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    }
                }

                Spacer(Modifier.height(22.dp))

                // Close button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ACCENT_COLOR.copy(alpha = 0.09f))
                        .border(
                            1.dp,
                            ACCENT_COLOR.copy(alpha = 0.27f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onDismiss() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text          = "CLOSE",
                        color         = ACCENT_COLOR,
                        fontSize      = 12.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}

// ── MetricCard (kept for compatibility) ──────────────────────────────────────
@Composable
fun MetricCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text       = value,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )
            Text(text = label, fontSize = 10.sp, color = Color(0xFF9E9E9E))
        }
    }
}