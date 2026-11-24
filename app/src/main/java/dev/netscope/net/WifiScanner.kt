package dev.netscope.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class WifiScanner(context: Context, private val oui: OuiDatabase) {
    private val appContext = context.applicationContext
    private val wm = appContext.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    fun results(): List<WifiNetwork> =
        runCatching { wm.scanResults.orEmpty().map(::toNetwork) }.getOrDefault(emptyList())

    /** Returns false when Android throttles the request (4 scans / 2 min in foreground). */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean = runCatching { wm.startScan() }.getOrDefault(false)

    fun updates(): Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.map { results() }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    private fun toNetwork(r: ScanResult): WifiNetwork {
        val freq = r.frequency
        val band = ChannelUtils.band(freq)
        val channel = ChannelUtils.channel(freq)
        val center = if (r.centerFreq0 > 0) ChannelUtils.channel(r.centerFreq0).takeIf { it > 0 } ?: channel else channel
        val standard = if (Build.VERSION.SDK_INT >= 30) ChannelUtils.standardLabel(r.wifiStandard, band) else null
        val caps = r.capabilities.orEmpty()
        return WifiNetwork(
            ssid = r.SSID.orEmpty(),
            bssid = r.BSSID.orEmpty(),
            level = r.level,
            frequency = freq,
            channel = channel,
            centerChannel = center,
            band = band,
            widthMhz = ChannelUtils.widthMhz(r.channelWidth),
            security = ChannelUtils.security(caps),
            wps = caps.contains("WPS"),
            standard = standard,
            vendor = oui.lookup(r.BSSID)?.takeIf { it != OuiDatabase.PRIVATE },
            capabilities = caps,
        )
    }
}
