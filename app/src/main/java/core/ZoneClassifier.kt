package com.signal_sense.core

import android.telephony.TelephonyManager

object ZoneClassifier {

    fun classify(data: SignalData): SignalZone {
        if (!data.isValid) return SignalZone.UNKNOWN

        val dbmScore  = scoreDbm(data.dbm, data.networkLabel)
        val sinrScore = if (!data.sinr.isNaN()) scoreSinr(data.sinr, data.networkLabel) else null
        val rsrqScore = if (!data.rsrq.isNaN()) scoreRsrq(data.rsrq, data.networkLabel) else null

        val scores = listOfNotNull(dbmScore, sinrScore, rsrqScore)
        val avg = scores.sum().toDouble() / scores.size

        return when {
            avg >= 1.7 -> SignalZone.STRONG
            avg >= 0.8 -> SignalZone.WEAK
            else       -> SignalZone.DEAD
        }
    }

    fun resolveNetworkLabel(networkType: Int): String = when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE          -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP         -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE           -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_NR            -> "5G NR"
        else                                        -> "Unknown"
    }

    // ── dBm thresholds per generation ────────────────────────────────────────
    // 2G  (GSM/EDGE):  Strong > -85,  Weak > -100, Dead <= -100
    // 3G  (UMTS/HSPA): Strong > -85,  Weak > -100, Dead <= -100
    // 4G  (LTE):       Strong > -95,  Weak > -110, Dead <= -110
    // 5G  (NR):        Strong > -100, Weak > -115, Dead <= -115
    private fun scoreDbm(dbm: Int, network: String): Int {
        val (strong, weak) = when {
            network == "2G"    -> Pair(-85,  -100)
            network == "3G"    -> Pair(-85,  -100)
            network == "4G LTE"-> Pair(-95,  -110)
            network == "5G NR" -> Pair(-100, -115)
            else               -> Pair(-95,  -110)
        }
        return when {
            dbm >= strong -> 2
            dbm >= weak   -> 1
            else          -> 0
        }
    }

    // ── SINR thresholds per generation ───────────────────────────────────────
    // 2G:  Not applicable — return neutral
    // 3G:  Strong > 6,   Weak > 0,   Dead <= 0
    // 4G:  Strong > 12,  Weak > 0,   Dead <= 0
    // 5G:  Strong > 10,  Weak > -3,  Dead <= -3
    private fun scoreSinr(sinr: Double, network: String): Int {
        return when (network) {
            "2G" -> 2  // 2G doesn't use SINR — don't penalize
            "3G" -> when {
                sinr >= 6.0  -> 2
                sinr >= 0.0  -> 1
                else         -> 0
            }
            "4G LTE" -> when {
                sinr >= 12.0 -> 2
                sinr >= 0.0  -> 1
                else         -> 0
            }
            "5G NR" -> when {
                sinr >= 10.0 -> 2
                sinr >= -3.0 -> 1
                else         -> 0
            }
            else -> when {
                sinr >= 10.0 -> 2
                sinr >= 0.0  -> 1
                else         -> 0
            }
        }
    }

    // ── RSRQ thresholds per generation ───────────────────────────────────────
    // 2G:  Not applicable — return neutral
    // 3G:  Not applicable — return neutral
    // 4G:  Strong > -10, Weak > -15, Dead <= -15
    // 5G:  Strong > -10, Weak > -17, Dead <= -17
    private fun scoreRsrq(rsrq: Double, network: String): Int {
        return when (network) {
            "2G" -> 2  // not applicable
            "3G" -> 2  // not applicable
            "4G LTE" -> when {
                rsrq >= -10.0 -> 2
                rsrq >= -15.0 -> 1
                else          -> 0
            }
            "5G NR" -> when {
                rsrq >= -10.0 -> 2
                rsrq >= -17.0 -> 1
                else          -> 0
            }
            else -> when {
                rsrq >= -10.0 -> 2
                rsrq >= -15.0 -> 1
                else          -> 0
            }
        }
    }
}