package com.signal_sense.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SignalMonitorService : Service() {

    companion object {
        const val CHANNEL_ID     = "signalsense_channel"
        const val NOTIF_ID       = 1001
        const val ALERT_NOTIF_ID = 1002
        const val ACTION_STOP    = "com.signal_sense.STOP"
    }

    private val monitor by lazy { SignalMonitor(applicationContext) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildStatusNotification("Starting RF monitor…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        monitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun postStatusNotification(nm: NotificationManager, notif: Notification) {
        nm.notify(NOTIF_ID, notif)
    }

    @SuppressLint("MissingPermission")
    private fun postAlertNotification(nm: NotificationManager, notif: Notification) {
        nm.notify(ALERT_NOTIF_ID, notif)
    }

    private fun startMonitoring() {
        monitor.start()

        scope.launch {
            monitor.signalFlow.collectLatest { data ->
                if (!data.isValid) return@collectLatest
                if (!canPostNotifications()) return@collectLatest
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val notif = buildStatusNotification(
                    "${data.zone.emoji} ${data.zone.label}  •  ${data.dbm} dBm  •  ${data.networkLabel}"
                )
                postStatusNotification(nm, notif)
            }
        }

        scope.launch {
            monitor.alertFlow.collectLatest { alert ->
                alert ?: return@collectLatest
                if (!canPostNotifications()) return@collectLatest
                if (alert.severity == AlertSeverity.CRITICAL ||
                    alert.severity == AlertSeverity.WARNING) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val notif = buildAlertNotification(alert)
                    postAlertNotification(nm, notif)
                }
            }
        }
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SignalSense")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun buildAlertNotification(alert: SignalAlert): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SignalSense Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Live RF signal monitoring" }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}