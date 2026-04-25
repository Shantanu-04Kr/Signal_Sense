package com.signal_sense.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    private val _signalFlow = MutableStateFlow(SignalData())
    val signalFlow: StateFlow<SignalData> = _signalFlow.asStateFlow()

    private val _alertFlow = MutableStateFlow<SignalAlert?>(null)
    val alertFlow: StateFlow<SignalAlert?> = _alertFlow.asStateFlow()

    // Connectivity description for UI — "Calls: ✓  Payments: ✗"
    private val _connectivityDesc = MutableStateFlow(Pair("Checking...", "Testing connectivity"))
    val connectivityDesc: StateFlow<Pair<String, String>> = _connectivityDesc.asStateFlow()

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var consecutiveDeadCount = 0

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun isRunning(): Boolean = monitorJob?.isActive == true

    private suspend fun poll() {
        if (!hasRequiredPermissions()) {
            _alertFlow.value = SignalAlert.PermissionDenied
            return
        }

        // 1. Read raw RF metrics
        val raw = readRawMetrics()

        // 2. Run real connectivity tests
        val connectivity = ConnectivityTester.test(context)

        // 3. Classify using BOTH RF + real connectivity
        val zone = ConnectivityTester.classifyFromBoth(raw, connectivity)

        // 4. Build final data object
        val data = raw.copy(zone = zone, isDeadZone = zone == SignalZone.DEAD)

        // 5. Generate human-readable description
        val desc = ConnectivityTester.describeConnectivity(zone, connectivity, data)

        // 6. Emit to flows
        _signalFlow.value = data
        _connectivityDesc.value = desc

        // 7. Fire alerts
        AlertEngine.evaluate(data, consecutiveDeadCount) { _alertFlow.value = it }
        consecutiveDeadCount =
            if (zone == SignalZone.DEAD) consecutiveDeadCount + 1 else 0

        // 8. Upload to Firebase
        FirebaseUploader.upload(data)
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
                    return SignalData(
                        dbm            = ss.dbm,
                        sinr           = ss.ssRsrp.toDouble(),
                        rsrq           = ss.ssRsrq.toDouble(),
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
        val networkType = telephonyManager.networkType
        val asu = telephonyManager.signalStrength?.gsmSignalStrength ?: 99
        val dbm = if (asu in 0..31) -113 + 2 * asu else Int.MIN_VALUE
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