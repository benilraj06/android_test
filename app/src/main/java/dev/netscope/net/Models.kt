package dev.netscope.net

enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz"), GHZ_60("60 GHz"), UNKNOWN("Unknown")
}

enum class WifiSecurity(val label: String, val weak: Boolean) {
    OPEN("Open", true),
    WEP("WEP", true),
    WPA("WPA", true),
    WPA2("WPA2", false),
    WPA2_WPA3("WPA2/WPA3", false),
    WPA3("WPA3", false),
    OWE("Enhanced Open", false),
    ENTERPRISE("Enterprise", false),
}

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val channel: Int,
    val centerChannel: Int,
    val band: WifiBand,
    val widthMhz: Int,
    val security: WifiSecurity,
    val wps: Boolean,
    val standard: String?,
    val vendor: String?,
    val capabilities: String,
) {
    val displaySsid: String get() = ssid.ifEmpty { "(hidden network)" }
}

data class ConnectionInfo(
    val isWifi: Boolean = false,
    val hasAnyNetwork: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val linkSpeedMbps: Int? = null,
    val txSpeedMbps: Int? = null,
    val rxSpeedMbps: Int? = null,
    val frequency: Int? = null,
    val wifiStandard: String? = null,
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val interfaceName: String? = null,
    val dhcpServer: String? = null,
    val routerVendor: String? = null,
) {
    val channel: Int? get() = frequency?.let { ChannelUtils.channel(it) }?.takeIf { it > 0 }
    val band: WifiBand? get() = frequency?.let { ChannelUtils.band(it) }
    val signalPercent: Int? get() = rssi?.let { Signal.percent(it) }
    val subnetMask: String? get() = prefixLength?.let { IpUtils.longToIp(IpUtils.maskFromPrefix(it)) }
}

object Signal {
    fun percent(rssi: Int): Int = ((rssi + 100) * 2).coerceIn(0, 100)
    fun bars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    fun quality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Fair"
        rssi >= -80 -> "Weak"
        else -> "Very weak"
    }
}

fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
