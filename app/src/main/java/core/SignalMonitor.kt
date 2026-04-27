package com.signal_sense.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SignalMonitor(private val context: Context) {

    companion object {
        const val POLL_INTERVAL_MS        = 5_000L
        const val DEAD_ZONE_TRIGGER_COUNT = 3
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _signalFlow = MutableStateFlow(SignalData())
    val signalFlow: StateFlow<SignalData> = _signalFlow.asStateFlow()

    private val _alertFlow = MutableStateFlow<SignalAlert?>(null)
    val alertFlow: StateFlow<SignalAlert?> = _alertFlow.asStateFlow()

    private val _connectivityDesc = MutableStateFlow(Pair("Checking...", "Testing connectivity"))
    val connectivityDesc: StateFlow<Pair<String, String>> = _connectivityDesc.asStateFlow()

    // Expose location as a flow so HeatmapActivity can use it
    private val _locationFlow = MutableStateFlow(Pair(0.0, 0.0))
    val locationFlow: StateFlow<Pair<Double, Double>> = _locationFlow.asStateFlow()

    private var monitorJob: Job? = null
    private var uploadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var consecutiveDeadCount = 0

    // Location — two separate volatile vars updated by listener
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0
    @Volatile private var locationAccuracy: Float = Float.MAX_VALUE

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Always prefer GPS — most accurate
            currentLat = location.latitude
            currentLng = location.longitude
            locationAccuracy = location.accuracy
            _locationFlow.value = Pair(currentLat, currentLng)
            android.util.Log.d("GPS",
                "📍 GPS fix: $currentLat, $currentLng acc:${location.accuracy}m")
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Only use network location if GPS hasn't given a fix yet
            // or if network is significantly more accurate
            if (currentLat == 0.0 && currentLng == 0.0) {
                currentLat = location.latitude
                currentLng = location.longitude
                locationAccuracy = location.accuracy
                _locationFlow.value = Pair(currentLat, currentLng)
                android.util.Log.d("GPS",
                    "📍 Network fix: $currentLat, $currentLng acc:${location.accuracy}m")
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        Handler(Looper.getMainLooper()).post {
            try {
                val hasGps     = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                // Start network first for fast initial fix
                if (hasNetwork) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L, 0f,
                        networkListener,
                        Looper.getMainLooper()
                    )
                    // Seed immediately
                    locationManager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER
                    )?.let {
                        if (currentLat == 0.0) {
                            currentLat = it.latitude
                            currentLng = it.longitude
                            _locationFlow.value = Pair(currentLat, currentLng)
                            android.util.Log.d("GPS",
                                "Seeded network: $currentLat, $currentLng")
                        }
                    }
                }

                // Then GPS for accuracy
                if (hasGps) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L, 0f,
                        gpsListener,
                        Looper.getMainLooper()
                    )
                    // GPS last known overrides network seed
                    locationManager.getLastKnownLocation(
                        LocationManager.GPS_PROVIDER
                    )?.let {
                        currentLat = it.latitude
                        currentLng = it.longitude
                        locationAccuracy = it.accuracy
                        _locationFlow.value = Pair(currentLat, currentLng)
                        android.util.Log.d("GPS",
                            "Seeded GPS: $currentLat, $currentLng")
                    }
                }

                android.util.Log.d("SignalMonitor",
                    "✅ Location started — GPS:$hasGps Network:$hasNetwork")
            } catch (e: Exception) {
                android.util.Log.e("SignalMonitor", "❌ Location error: ${e.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        Handler(Looper.getMainLooper()).post {
            try {
                locationManager.removeUpdates(gpsListener)
                locationManager.removeUpdates(networkListener)
            } catch (e: Exception) { }
        }
    }

    fun start() {
        if (monitorJob?.isActive == true) return
        startLocationUpdates()

        // Job 1 — RF + connectivity polling (may take variable time)
        monitorJob = scope.launch {
            while (isActive) {
                val startMs = System.currentTimeMillis()
                poll()
                // Always wait exactly POLL_INTERVAL_MS from start
                val elapsed = System.currentTimeMillis() - startMs
                val remaining = POLL_INTERVAL_MS - elapsed
                if (remaining > 0) delay(remaining)
            }
        }

        // Job 2 — Firebase upload on exact 5 second timer
        // Completely separate from poll — never affected by connectivity test duration
        uploadJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val data = _signalFlow.value
                if (data.isValid) {
                    FirebaseUploader.upload(data, currentLat, currentLng)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        uploadJob?.cancel()
        monitorJob = null
        uploadJob = null
        stopLocationUpdates()
    }

    fun isRunning(): Boolean = monitorJob?.isActive == true

    private suspend fun poll() {
        if (!hasRequiredPermissions()) {
            _alertFlow.value = SignalAlert.PermissionDenied
            return
        }

        val raw          = readRawMetrics()
        val connectivity = ConnectivityTester.test(context)
        val zone         = ConnectivityTester.classifyFromBoth(raw, connectivity)
        val data         = raw.copy(zone = zone, isDeadZone = zone == SignalZone.DEAD)
        val desc         = ConnectivityTester.describeConnectivity(zone, connectivity, data)

        _signalFlow.value       = data
        _connectivityDesc.value = desc

        AlertEngine.evaluate(data, consecutiveDeadCount) { _alertFlow.value = it }
        consecutiveDeadCount =
            if (zone == SignalZone.DEAD) consecutiveDeadCount + 1 else 0
    }

    @SuppressLint("MissingPermission")
    private fun readRawMetrics(): SignalData {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) readModern()
            else readLegacy()
        } catch (e: SecurityException) {
            SignalData()
        }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun readModern(): SignalData {
        val cellInfoList = telephonyManager.allCellInfo ?: return readLegacy()
        val networkType  = telephonyManager.dataNetworkType
        for (cell in cellInfoList) {
            when (cell) {
                is CellInfoLte -> {
                    val ss = cell.cellSignalStrength
                    return SignalData(
                        dbm            = ss.dbm,
                        sinr           = ss.rssnr.toDouble(),
                        rsrq           = ss.rsrq.toDouble(),
                        networkTypeRaw = networkType,
                        networkLabel   = "4G LTE"
                    )
                }
                is CellInfoNr -> {
                    val ss = cell.cellSignalStrength as CellSignalStrengthNr
                    val nrDbm = when {
                        ss.ssRsrp != Int.MIN_VALUE -> ss.ssRsrp
                        ss.dbm != Int.MIN_VALUE    -> ss.dbm
                        else                       -> continue
                    }
                    val nrSinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ss.ssSinr != Int.MIN_VALUE) ss.ssSinr.toDouble() else Double.NaN
                    val nrRsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ss.ssRsrq != Int.MIN_VALUE) ss.ssRsrq.toDouble() else Double.NaN
                    return SignalData(
                        dbm            = nrDbm,
                        sinr           = nrSinr,
                        rsrq           = nrRsrq,
                        networkTypeRaw = networkType,
                        networkLabel   = "5G NR"
                    )
                }
                is CellInfoWcdma -> return SignalData(
                    dbm            = cell.cellSignalStrength.dbm,
                    networkTypeRaw = networkType,
                    networkLabel   = "3G"
                )
                is CellInfoGsm -> return SignalData(
                    dbm            = cell.cellSignalStrength.dbm,
                    networkTypeRaw = networkType,
                    networkLabel   = "2G"
                )
            }
        }
        return readLegacy()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readLegacy(): SignalData {
        val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            telephonyManager.dataNetworkType
        } else {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val dbm = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val level = telephonyManager.signalStrength?.level ?: 0
                when (level) {
                    4    -> -65
                    3    -> -80
                    2    -> -95
                    1    -> -110
                    else -> Int.MIN_VALUE
                }
            } else { -85 }
        } catch (e: Exception) { Int.MIN_VALUE }

        return SignalData(
            dbm            = dbm,
            networkTypeRaw = networkType,
            networkLabel   = ZoneClassifier.resolveNetworkLabel(networkType)
        )
    }

    private fun hasRequiredPermissions(): Boolean {
        val phone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return phone && loc
    }
}