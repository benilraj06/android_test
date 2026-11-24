package dev.netscope.net

object ChannelUtils {
    fun channel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 4910..4980 -> (freq - 4000) / 5
        freq in 5000..5900 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq in 5950..7125 -> (freq - 5950) / 5
        freq in 58320..70200 -> (freq - 56160) / 2160
        else -> 0
    }

    fun band(freq: Int): WifiBand = when {
        freq in 2400..2500 -> WifiBand.GHZ_2_4
        freq in 4900..5900 -> WifiBand.GHZ_5
        freq in 5925..7125 -> WifiBand.GHZ_6
        freq >= 57000 -> WifiBand.GHZ_60
        else -> WifiBand.UNKNOWN
    }

    fun widthMhz(code: Int): Int = when (code) {
        0 -> 20
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> 20
    }

    fun standardLabel(code: Int, band: WifiBand?): String? = when (code) {
        1 -> "Legacy (a/b/g)"
        4 -> "Wi-Fi 4 (n)"
        5 -> "Wi-Fi 5 (ac)"
        6 -> if (band == WifiBand.GHZ_6) "Wi-Fi 6E (ax)" else "Wi-Fi 6 (ax)"
        7 -> "WiGig (ad)"
        8 -> "Wi-Fi 7 (be)"
        else -> null
    }

    fun security(capabilities: String): WifiSecurity {
        val c = capabilities.uppercase()
        return when {
            c.contains("EAP") -> WifiSecurity.ENTERPRISE
            c.contains("SAE") && c.contains("PSK") -> WifiSecurity.WPA2_WPA3
            c.contains("SAE") || c.contains("WPA3") -> WifiSecurity.WPA3
            c.contains("OWE") -> WifiSecurity.OWE
            c.contains("RSN") || c.contains("WPA2") -> WifiSecurity.WPA2
            c.contains("WPA") -> WifiSecurity.WPA
            c.contains("WEP") -> WifiSecurity.WEP
            else -> WifiSecurity.OPEN
        }
    }

    data class ChannelScore(val channel: Int, val score: Float, val count: Int)

    /** Lower score = less congested. Weighted by overlap and signal strength. */
    fun channelScores(networks: List<WifiNetwork>, band: WifiBand): List<ChannelScore> {
        val candidates = when (band) {
            WifiBand.GHZ_2_4 -> listOf(1, 6, 11)
            WifiBand.GHZ_5 -> listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
            WifiBand.GHZ_6 -> listOf(5, 21, 37, 53, 69, 85, 101, 117)
            else -> emptyList()
        }
        val inBand = networks.filter { it.band == band }
        return candidates.map { ch ->
            var score = 0f
            var count = 0
            inBand.forEach { n ->
                val reach = n.widthMhz / 10f + 2f
                val dist = kotlin.math.abs(n.centerChannel - ch).toFloat()
                if (dist < reach) {
                    score += (1f - dist / reach) * ((n.level + 100).coerceAtLeast(0) / 60f)
                    count++
                }
            }
            ChannelScore(ch, score, count)
        }
    }
}
