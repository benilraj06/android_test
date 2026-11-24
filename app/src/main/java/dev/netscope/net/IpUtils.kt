package dev.netscope.net

object IpUtils {
    fun isValidIpv4(s: String): Boolean {
        val p = s.trim().split('.')
        return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    }

    fun ipToLong(ip: String): Long =
        ip.trim().split('.').fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }

    fun longToIp(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    /** WifiInfo.ipAddress is little-endian. */
    fun intToIp(v: Int): String =
        "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v ushr 24) and 0xFF}"

    fun maskFromPrefix(prefix: Int): Long =
        if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL

    data class ScanRange(val cidr: String, val hosts: List<String>)

    /** Hosts to sweep. Large subnets are narrowed to the /24 around our address. */
    fun scanRange(ip: String, prefix: Int): ScanRange {
        val p = if (prefix < 22) 24 else prefix.coerceAtMost(30)
        val mask = maskFromPrefix(p)
        val network = ipToLong(ip) and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val hosts = ((network + 1) until broadcast).map { longToIp(it) }
        return ScanRange("${longToIp(network)}/$p", hosts)
    }

    data class SubnetInfo(
        val cidr: String,
        val network: String,
        val broadcast: String,
        val mask: String,
        val wildcard: String,
        val firstHost: String,
        val lastHost: String,
        val hostCount: Long,
        val ipClass: String,
        val isPrivate: Boolean,
    )

    fun subnetInfo(ip: String, prefix: Int): SubnetInfo {
        val mask = maskFromPrefix(prefix)
        val ipL = ipToLong(ip)
        val network = ipL and mask
        val wildcard = mask.inv() and 0xFFFFFFFFL
        val broadcast = network or wildcard
        val first: Long
        val last: Long
        val count: Long
        when (prefix) {
            32 -> { first = network; last = network; count = 1 }
            31 -> { first = network; last = broadcast; count = 2 }
            else -> { first = network + 1; last = broadcast - 1; count = broadcast - network - 1 }
        }
        val o1 = ((ipL shr 24) and 0xFF).toInt()
        val o2 = ((ipL shr 16) and 0xFF).toInt()
        val cls = when (o1) {
            in 0..127 -> "A"
            in 128..191 -> "B"
            in 192..223 -> "C"
            in 224..239 -> "D (multicast)"
            else -> "E (reserved)"
        }
        val private = o1 == 10 || (o1 == 172 && o2 in 16..31) || (o1 == 192 && o2 == 168) || (o1 == 100 && o2 in 64..127)
        return SubnetInfo(
            cidr = "${longToIp(network)}/$prefix",
            network = longToIp(network),
            broadcast = longToIp(broadcast),
            mask = longToIp(mask),
            wildcard = longToIp(wildcard),
            firstHost = longToIp(first),
            lastHost = longToIp(last),
            hostCount = count,
            ipClass = cls,
            isPrivate = private,
        )
    }
}
