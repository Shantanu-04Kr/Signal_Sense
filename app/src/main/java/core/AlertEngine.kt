package com.signal_sense.core

sealed class SignalAlert(
    open val title: String,
    open val message: String,
    open val severity: AlertSeverity
) {
    data class DeadZoneEntered(
        override val title: String = "Dead zone detected",
        override val message: String = "Move to restore connectivity.",
        override val severity: AlertSeverity = AlertSeverity.CRITICAL
    ) : SignalAlert(title, message, severity)

    data class SignalWeak(
        val dbm: Int,
        override val title: String = "Weak signal",
        override val message: String = "Signal dropping. Consider moving.",
        override val severity: AlertSeverity = AlertSeverity.WARNING
    ) : SignalAlert(title, message, severity)

    data class SignalRestored(
        override val title: String = "Signal restored",
        override val message: String = "You are back in a good coverage area.",
        override val severity: AlertSeverity = AlertSeverity.INFO
    ) : SignalAlert(title, message, severity)

    object PermissionDenied : SignalAlert(
        title    = "Permission required",
        message  = "READ_PHONE_STATE and ACCESS_FINE_LOCATION are needed.",
        severity = AlertSeverity.WARNING
    )
}

enum class AlertSeverity { INFO, WARNING, CRITICAL }

object AlertEngine {
    fun evaluate(
        data: SignalData,
        consecutiveDead: Int,
        emit: (SignalAlert) -> Unit
    ) {
        when (data.zone) {
            SignalZone.DEAD -> {
                if (consecutiveDead >= 2) emit(SignalAlert.DeadZoneEntered())
            }
            SignalZone.WEAK -> {
                emit(SignalAlert.SignalWeak(dbm = data.dbm))
            }
            SignalZone.STRONG -> {
                // Clear any previous alert when signal is strong
                emit(SignalAlert.SignalRestored())
            }
            SignalZone.UNKNOWN -> {}
        }
    }
}