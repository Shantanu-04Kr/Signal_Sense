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
        const val POLL_INTERVAL_MS = 5_000L
        const val DEAD_ZONE_TRIGGER_COUNT = 3
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _signalFlow = MutableStateFlow(SignalData())
    val signalFlow: StateFlow<SignalData> = _signalFlow.asStateFlow()

    private val _alertFlow = MutableStateFlow<SignalAlert?>(null)
    val alertFlow: StateFlow<SignalAlert?> = _alertFlow.asStateFlow()

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

    private fun poll() {
        if (!hasRequiredPermissions()) {
            _alertFlow.value = SignalAlert.PermissionDenied
            return
        }

        val raw = readRawMetrics()
        val zone = ZoneClassifier.classify(raw)

        val data = raw.copy(
            zone = zone,
            isDeadZone = zone == SignalZone.DEAD
        )

        _signalFlow.value = data

        AlertEngine.evaluate(data, consecutiveDeadCount) {
            _alertFlow.value = it
        }

        consecutiveDeadCount =
            if (zone == SignalZone.DEAD) consecutiveDeadCount + 1 else 0
    }

    private fun readRawMetrics(): SignalData {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readModern()
            } else {
                readLegacy()
            }
        } catch (e: SecurityException) {
            SignalData()
        }
    }

    // ===========================
    // MODERN (API 29+)
    // ===========================

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun readModern(): SignalData {

        val cellInfoList = telephonyManager.allCellInfo ?: return readLegacy()
        val networkType = telephonyManager.dataNetworkType

        for (cell in cellInfoList) {

            when (cell) {

                is CellInfoLte -> {
                    val ss = cell.cellSignalStrength
                    return SignalData(
                        dbm = ss.dbm,
                        sinr = ss.rssnr.toDouble(),
                        rsrq = ss.rsrq.toDouble(),
                        networkTypeRaw = networkType,
                        networkLabel = "4G LTE"
                    )
                }

                is CellInfoNr -> {
                    val ss = cell.cellSignalStrength as CellSignalStrengthNr
                    return SignalData(
                        dbm = ss.dbm,
                        sinr = ss.ssRsrp.toDouble(),
                        rsrq = ss.ssRsrq.toDouble(),
                        networkTypeRaw = networkType,
                        networkLabel = "5G NR"
                    )
                }

                is CellInfoWcdma -> {
                    return SignalData(
                        dbm = cell.cellSignalStrength.dbm,
                        networkTypeRaw = networkType,
                        networkLabel = "3G"
                    )
                }

                is CellInfoGsm -> {
                    return SignalData(
                        dbm = cell.cellSignalStrength.dbm,
                        networkTypeRaw = networkType,
                        networkLabel = "2G"
                    )
                }
            }
        }

        return readLegacy()
    }

    // ===========================
    // LEGACY (API 26–28 SAFE)
    // ===========================

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readLegacy(): SignalData {

        val networkType = telephonyManager.networkType

        val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            telephonyManager.signalStrength?.level?.let { level ->
                when (level) {
                    4 -> -75
                    3 -> -85
                    2 -> -95
                    1 -> -105
                    else -> -120
                }
            } ?: Int.MIN_VALUE
        } else {
            Int.MIN_VALUE
        }

        return SignalData(
            dbm = dbm,
            networkTypeRaw = networkType,
            networkLabel = ZoneClassifier.resolveNetworkLabel(networkType)
        )
    }

    // ===========================
    // PERMISSION CHECK
    // ===========================

    private fun hasRequiredPermissions(): Boolean {

        val phonePermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

        val locationPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return phonePermission && locationPermission
    }
}