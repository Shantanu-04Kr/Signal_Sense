package com.signal_sense.core

import android.telephony.TelephonyManager

/**
 * Represents complete signal + usability state at a given time.
 * Combines RF metrics and real-world connectivity usability.
 */
data class SignalData(

    // ── Time ─────────────────────────────────────────────
    val timestampMs: Long = System.currentTimeMillis(),

    // ── RF Metrics ───────────────────────────────────────
    val dbm: Int = Int.MIN_VALUE,
    val sinr: Double = Double.NaN,
    val rsrq: Double = Double.NaN,

    // ── Network Info ─────────────────────────────────────
    val networkTypeRaw: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN,
    val networkLabel: String = "Unknown",

    // ── AI / Classification ──────────────────────────────
    val zone: SignalZone = SignalZone.UNKNOWN,
    val isDeadZone: Boolean = false,

    // ── Real Usability Percentages (NEW 🔥) ──────────────
    val callPercent: Int = 0,
    val dataPercent: Int = 0,
    val paymentPercent: Int = 0

) {

    // ── Derived Helpers ───────────────────────────────────

    val isValid: Boolean
        get() = dbm != Int.MIN_VALUE

    val averageUsability: Int
        get() = (callPercent + dataPercent + paymentPercent) / 3

    val isUsable: Boolean
        get() = averageUsability > 40
}

/**
 * Signal strength classification used for UI coloring + AI decisions
 */
enum class SignalZone(val label: String, val emoji: String) {
    STRONG("Strong",    "🟢"),
    WEAK  ("Weak",      "🟡"),
    DEAD  ("Dead Zone", "🔴"),
    UNKNOWN("Unknown",  "⚪")
}