package dev.netscope.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address

class NetworkInfoProvider(context: Context, private val oui: OuiDatabase) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wm = appContext.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun current(): ConnectionInfo {
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val wifiNetwork = networks.firstOrNull { n ->
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val hasAny = runCatching { cm.activeNetwork != null }.getOrDefault(false)
        if (wifiNetwork == null) return ConnectionInfo(isWifi = false, hasAnyNetwork = hasAny)

        val lp = runCatching { cm.getLinkProperties(wifiNetwork) }.getOrNull()
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val ipv6 = lp?.linkAddresses?.filter { it.address is Inet6Address }
            ?.mapNotNull { it.address.hostAddress?.substringBefore('%') } ?: emptyList()
        val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress?.takeIf { it != "0.0.0.0" }
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList()
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null

        val info = runCatching { wm.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
        val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        val rssi = info?.rssi?.takeIf { it in -126..-1 }
        val freq = info?.frequency?.takeIf { it > 0 }
        val standard = if (Build.VERSION.SDK_INT >= 30 && info != null) {
            ChannelUtils.standardLabel(info.wifiStandard, freq?.let { ChannelUtils.band(it) })
        } else null
        val tx = if (Build.VERSION.SDK_INT >= 29) info?.txLinkSpeedMbps?.takeIf { it > 0 } else null
        val rx = if (Build.VERSION.SDK_INT >= 29) info?.rxLinkSpeedMbps?.takeIf { it > 0 } else null
        val ipv4 = v4?.address?.hostAddress ?: info?.ipAddress?.takeIf { it != 0 }?.let { IpUtils.intToIp(it) }

        return ConnectionInfo(
            isWifi = true,
            hasAnyNetwork = true,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            txSpeedMbps = tx,
            rxSpeedMbps = rx,
            frequency = freq,
            wifiStandard = standard,
            ipv4 = ipv4,
            prefixLength = v4?.prefixLength ?: 24,
            gateway = gateway,
            dns = dns,
            ipv6 = ipv6,
            interfaceName = lp?.interfaceName,
            dhcpServer = dhcp,
            routerVendor = oui.lookup(bssid)?.takeIf { it != OuiDatabase.PRIVATE },
        )
    }
}
