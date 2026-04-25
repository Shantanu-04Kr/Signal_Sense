package com.signal_sense.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Performs real-world connectivity tests to determine actual usability.
 * Goes beyond RF metrics — tests if payments, calls, and data actually work.
 */
object ConnectivityTester {

    // Test endpoints — multiple for reliability
    private val PING_HOSTS = listOf(
        "8.8.8.8",           // Google DNS
        "1.1.1.1",           // Cloudflare DNS
        "208.67.222.222"     // OpenDNS
    )

    private val HTTP_ENDPOINTS = listOf(
        "https://www.google.com",
        "https://www.cloudflare.com"
    )

    // Payment gateways — if these are reachable, UPI/payments work
    private val PAYMENT_ENDPOINTS = listOf(
        "https://api.razorpay.com",
        "https://securegw.paytm.in",
        "https://api.phonepe.com"
    )

    /**
     * Full connectivity result with real-world usability scores.
     */
    data class ConnectivityResult(
        val canPing: Boolean = false,
        val canReachHttp: Boolean = false,
        val canReachPayments: Boolean = false,
        val pingLatencyMs: Long = -1,
        val httpLatencyMs: Long = -1,
        val isRoaming: Boolean = false,
        val hasActiveNetwork: Boolean = false,
        val networkType: NetworkType = NetworkType.NONE
    )

    enum class NetworkType { NONE, WIFI, CELLULAR, UNKNOWN }

    /**
     * Run all connectivity tests and return combined result.
     * Call this from a coroutine (it's a suspend function).
     */
    suspend fun test(context: Context): ConnectivityResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Check basic network availability first
        val hasNetwork = checkActiveNetwork(cm)
        if (!hasNetwork) {
            return ConnectivityResult(hasActiveNetwork = false)
        }

        val networkType = getNetworkType(cm)
        val isRoaming = tm.isNetworkRoaming

        // Run tests in parallel mentally — ping first (fastest)
        val pingResult = testPing()
        val httpResult = if (pingResult.first) testHttp() else Pair(false, -1L)
        val paymentResult = if (httpResult.first) testPayments() else false

        return ConnectivityResult(
            canPing           = pingResult.first,
            canReachHttp      = httpResult.first,
            canReachPayments  = paymentResult,
            pingLatencyMs     = pingResult.second,
            httpLatencyMs     = httpResult.second,
            isRoaming         = isRoaming,
            hasActiveNetwork  = true,
            networkType       = networkType
        )
    }

    /**
     * Classify zone based on BOTH RF metrics AND real connectivity.
     * This is the smart classification that replaces pure RF-based logic.
     */
    fun classifyFromBoth(
        rfData: SignalData,
        connectivity: ConnectivityResult
    ): SignalZone {

        // No active network at all = definitely dead
        if (!connectivity.hasActiveNetwork) return SignalZone.DEAD

        // Can't ping = dead (no real data path)
        if (!connectivity.canPing) return SignalZone.DEAD

        // Can ping but can't reach HTTP = weak (very limited connectivity)
        if (!connectivity.canReachHttp) return SignalZone.WEAK

        // Now factor in latency quality
        val latencyScore = when {
            connectivity.pingLatencyMs < 0   -> 0  // failed
            connectivity.pingLatencyMs < 100 -> 3  // excellent
            connectivity.pingLatencyMs < 300 -> 2  // good
            connectivity.pingLatencyMs < 600 -> 1  // poor
            else                             -> 0  // unusable
        }

        val httpLatencyScore = when {
            connectivity.httpLatencyMs < 0    -> 0
            connectivity.httpLatencyMs < 500  -> 3
            connectivity.httpLatencyMs < 1500 -> 2
            connectivity.httpLatencyMs < 3000 -> 1
            else                              -> 0
        }

        // RF score from signal metrics
        val rfScore = rfScore(rfData)

        // Payment reachability bonus
        val paymentBonus = if (connectivity.canReachPayments) 1 else 0

        // Weighted total score
        val total = (latencyScore * 0.35) +
                (httpLatencyScore * 0.25) +
                (rfScore * 0.30) +
                (paymentBonus * 0.10)

        return when {
            total >= 2.0 -> SignalZone.STRONG   // calls work, payments go through
            total >= 1.0 -> SignalZone.WEAK     // browsing works, payments may fail
            else         -> SignalZone.DEAD     // nothing works reliably
        }
    }

    /**
     * Generate a human-readable description of what works and what doesn't.
     */
    fun describeConnectivity(
        zone: SignalZone,
        connectivity: ConnectivityResult,
        rfData: SignalData
    ): Pair<String, String> {
        return when (zone) {
            SignalZone.STRONG -> Pair(
                "Good connectivity",
                buildString {
                    append("Calls: ✓  ")
                    append("Payments: ${if (connectivity.canReachPayments) "✓" else "~"}  ")
                    append("Data: ✓  ")
                    append("Ping: ${connectivity.pingLatencyMs}ms")
                }
            )
            SignalZone.WEAK -> Pair(
                "Weak connectivity",
                buildString {
                    append("Calls: ~  ")
                    append("Payments: ✗  ")
                    append("Data: ~  ")
                    if (connectivity.pingLatencyMs > 0)
                        append("Ping: ${connectivity.pingLatencyMs}ms")
                    else
                        append("Server unreachable")
                }
            )
            SignalZone.DEAD -> Pair(
                "No connectivity",
                buildString {
                    append("Calls: ✗  ")
                    append("Payments: ✗  ")
                    append("Data: ✗  ")
                    if (!connectivity.hasActiveNetwork) append("No network")
                    else if (!connectivity.canPing) append("Server unreachable")
                    else append("Connection unusable")
                }
            )
            SignalZone.UNKNOWN -> Pair("Checking...", "Testing connectivity")
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun checkActiveNetwork(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun getNetworkType(cm: ConnectivityManager): NetworkType {
        val network = cm.activeNetwork ?: return NetworkType.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.UNKNOWN
        }
    }

    private suspend fun testPing(): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        for (host in PING_HOSTS) {
            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(2000) {
                    InetAddress.getByName(host).isReachable(2000)
                }
                val latency = System.currentTimeMillis() - start
                if (result == true) return@withContext Pair(true, latency)
            } catch (e: Exception) {
                continue
            }
        }
        Pair(false, -1L)
    }

    private suspend fun testHttp(): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        for (endpoint in HTTP_ENDPOINTS) {
            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(3000) {
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..399
                }
                val latency = System.currentTimeMillis() - start
                if (result == true) return@withContext Pair(true, latency)
            } catch (e: Exception) {
                continue
            }
        }
        Pair(false, -1L)
    }

    private suspend fun testPayments(): Boolean = withContext(Dispatchers.IO) {
        for (endpoint in PAYMENT_ENDPOINTS) {
            try {
                val result = withTimeoutOrNull(3000) {
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..499  // any response = server reachable
                }
                if (result == true) return@withContext true
            } catch (e: Exception) {
                continue
            }
        }
        false
    }

    private fun rfScore(data: SignalData): Double {
        val dbmScore = when (data.networkLabel) {
            "2G"     -> when { data.dbm >= -85  -> 3.0; data.dbm >= -100 -> 1.5; else -> 0.0 }
            "3G"     -> when { data.dbm >= -85  -> 3.0; data.dbm >= -100 -> 1.5; else -> 0.0 }
            "4G LTE" -> when { data.dbm >= -95  -> 3.0; data.dbm >= -110 -> 1.5; else -> 0.0 }
            "5G NR"  -> when { data.dbm >= -100 -> 3.0; data.dbm >= -115 -> 1.5; else -> 0.0 }
            else     -> when { data.dbm >= -95  -> 3.0; data.dbm >= -110 -> 1.5; else -> 0.0 }
        }
        return dbmScore
    }
}
