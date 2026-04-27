package com.signal_sense.core

import android.telephony.TelephonyManager

data class SignalData(
    val timestampMs: Long = System.currentTimeMillis(),
    val dbm: Int = Int.MIN_VALUE,
    val sinr: Double = Double.NaN,
    val rsrq: Double = Double.NaN,
    val networkTypeRaw: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN,
    val networkLabel: String = "Unknown",
    val zone: SignalZone = SignalZone.UNKNOWN,
    val isDeadZone: Boolean = false
) {
    val isValid: Boolean get() = dbm != Int.MIN_VALUE
}

enum class SignalZone(val label: String, val emoji: String) {
    STRONG("Strong",    "🟢"),
    WEAK  ("Weak",      "🟡"),
    DEAD  ("Dead Zone", "🔴"),
    UNKNOWN("Unknown",  "⚪")
}