package com.signal_sense

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

class HeatmapActivity : ComponentActivity() {

    data class SignalReading(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val dbm: Int = 0,
        val zone: String = "",
        val network: String = "",
        val timestamp: Long = 0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            this, PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName
        setContent { HeatmapScreen() }
    }

    @Composable
    fun HeatmapScreen() {
        var readings by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var readingCount by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            val db = FirebaseDatabase
                .getInstance("https://signalsense-bcc2d-default-rtdb.firebaseio.com/")
                .reference

            db.child("readings").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<SignalReading>()
                    for (dateSnap in snapshot.children) {
                        for (timeSnap in dateSnap.children) {
                            val dbm  = timeSnap.child("dbm").getValue(Int::class.java) ?: continue
                            val zone = timeSnap.child("zone").getValue(String::class.java) ?: continue
                            val net  = timeSnap.child("network").getValue(String::class.java) ?: ""
                            val lat  = timeSnap.child("lat").getValue(Double::class.java) ?: 0.0
                            val lng  = timeSnap.child("lng").getValue(Double::class.java) ?: 0.0
                            val ts   = timeSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                            if (lat != 0.0 && lng != 0.0) {
                                list.add(SignalReading(lat, lng, dbm, zone, net, ts))
                            }
                        }
                    }
                    readings = list
                    readingCount = list.size
                    isLoading = false
                }
                override fun onCancelled(error: DatabaseError) {
                    isLoading = false
                }
            })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A2E))
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16213E))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Signal Heatmap",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "$readingCount readings",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E)
                )
            }

            // Map
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF4CAF50))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Loading signal data...", color = Color.White)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(16.0)
                                controller.setCenter(GeoPoint(12.9716, 77.5946))
                            }
                        },
                        update = { mapView ->
                            mapView.overlays.clear()
                            readings.forEach { reading ->
                                val circle = Polygon().apply {
                                    points = Polygon.pointsAsCircle(
                                        GeoPoint(reading.lat, reading.lng),
                                        15.0
                                    )
                                    fillPaint.apply {
                                        color = when (reading.zone) {
                                            "Strong"    -> android.graphics.Color.parseColor("#4CAF50")
                                            "Weak"      -> android.graphics.Color.parseColor("#FFC107")
                                            "Dead Zone" -> android.graphics.Color.parseColor("#F44336")
                                            else        -> android.graphics.Color.parseColor("#9E9E9E")
                                        }
                                        alpha = 80
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    outlinePaint.apply {
                                        color = when (reading.zone) {
                                            "Strong"    -> android.graphics.Color.parseColor("#4CAF50")
                                            "Weak"      -> android.graphics.Color.parseColor("#FFC107")
                                            "Dead Zone" -> android.graphics.Color.parseColor("#F44336")
                                            else        -> android.graphics.Color.parseColor("#9E9E9E")
                                        }
                                        alpha = 180
                                        strokeWidth = 1.5f
                                    }
                                    title = "${reading.zone} • ${reading.dbm} dBm • ${reading.network}"
                                }
                                mapView.overlays.add(circle)
                            }
                            if (readings.isNotEmpty()) {
                                mapView.controller.setCenter(
                                    GeoPoint(readings.last().lat, readings.last().lng)
                                )
                            }
                            mapView.invalidate()
                        }
                    )
                }
            }

            // Legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16213E))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LegendItem(color = Color(0xFF4CAF50), label = "Strong")
                LegendItem(color = Color(0xFFFFC107), label = "Weak")
                LegendItem(color = Color(0xFFF44336), label = "Dead")
            }
        }
    }

    @Composable
    fun LegendItem(color: Color, label: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, RoundedCornerShape(6.dp))
            )
            Text(text = label, fontSize = 12.sp, color = Color.White)
        }
    }
}