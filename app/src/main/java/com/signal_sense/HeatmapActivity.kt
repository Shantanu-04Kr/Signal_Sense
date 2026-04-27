package com.signal_sense

import android.annotation.SuppressLint
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
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

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

    @SuppressLint("MissingPermission")
    @Composable
    fun HeatmapScreen() {
        var readings     by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
        var isLoading    by remember { mutableStateOf(true) }
        var readingCount by remember { mutableStateOf(0) }
        var mapViewRef   by remember { mutableStateOf<MapView?>(null) }
        var myLocOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

        // Load from Firebase — live listener updates map as new data arrives
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
                    readings     = list.sortedBy { it.timestamp }
                    readingCount = list.size
                    isLoading    = false

                    // Center map on latest reading
                    if (list.isNotEmpty()) {
                        val last = list.last()
                        mapViewRef?.controller?.animateTo(GeoPoint(last.lat, last.lng))
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    isLoading = false
                }
            })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020617))
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) {
                        Text(text = "←", color = Color(0xFF94A3B8), fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "SIGNAL HEATMAP",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF22D3EE)
                    )
                }
                Text(
                    text = "$readingCount pts",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
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
                            CircularProgressIndicator(color = Color(0xFF22D3EE))
                            Spacer(Modifier.height(12.dp))
                            Text("Loading signal data...", color = Color.White)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(18.0) // zoomed in more for precision
                                controller.setCenter(GeoPoint(12.9716, 77.5946))

                                // ── Blue dot showing YOUR current location ──
                                val locationOverlay = MyLocationNewOverlay(
                                    GpsMyLocationProvider(ctx), this
                                ).apply {
                                    enableMyLocation()
                                    enableFollowLocation()   // map follows you as you move
                                    runOnFirstFix {
                                        post {
                                            controller.animateTo(myLocation)
                                            controller.setZoom(18.0)
                                        }
                                    }
                                }
                                overlays.add(locationOverlay)
                                myLocOverlay = locationOverlay
                                mapViewRef = this
                            }
                        },
                        update = { mapView ->
                            // Remove old signal overlays but keep location overlay
                            mapView.overlays.removeAll { it is Polygon || it is Marker }

                            // Draw signal circles
                            readings.forEach { reading ->
                                val circle = Polygon().apply {
                                    points = Polygon.pointsAsCircle(
                                        GeoPoint(reading.lat, reading.lng),
                                        5.0  // 5 meter radius
                                    )
                                    fillPaint.apply {
                                        color = when (reading.zone) {
                                            "Strong"    -> android.graphics.Color.parseColor("#4CAF50")
                                            "Weak"      -> android.graphics.Color.parseColor("#FFC107")
                                            "Dead Zone" -> android.graphics.Color.parseColor("#F44336")
                                            else        -> android.graphics.Color.parseColor("#9E9E9E")
                                        }
                                        alpha = 90
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    outlinePaint.apply {
                                        color = when (reading.zone) {
                                            "Strong"    -> android.graphics.Color.parseColor("#4CAF50")
                                            "Weak"      -> android.graphics.Color.parseColor("#FFC107")
                                            "Dead Zone" -> android.graphics.Color.parseColor("#F44336")
                                            else        -> android.graphics.Color.parseColor("#9E9E9E")
                                        }
                                        alpha = 200
                                        strokeWidth = 2f
                                    }
                                    title = "${reading.zone} • ${reading.dbm}dBm • ${reading.network}"
                                }
                                mapView.overlays.add(circle)
                            }

                            // Re-add location overlay on top so it's always visible
                            myLocOverlay?.let { overlay ->
                                if (!mapView.overlays.contains(overlay)) {
                                    mapView.overlays.add(overlay)
                                }
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
                    .background(Color(0xFF0F172A))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem(color = Color(0xFF4CAF50), label = "Strong")
                LegendItem(color = Color(0xFFFFC107), label = "Weak")
                LegendItem(color = Color(0xFFF44336), label = "Dead")
                LegendItem(color = Color(0xFF2196F3), label = "You")
            }
        }
    }

    @Composable
    fun LegendItem(color: Color, label: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, RoundedCornerShape(5.dp))
            )
            Text(text = label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        }
    }
}