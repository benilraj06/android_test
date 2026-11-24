package dev.netscope.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class MdnsInfo(
    val hostname: String?,
    val services: Set<String>,
    val labels: List<String>,
    val model: String?,
)

/** Bonjour / mDNS discovery using legacy-unicast queries (responses come straight back to us). */
object MdnsDiscovery {
    private val SERVICE_TYPES = listOf(
        "_services._dns-sd._udp.local",
        "_googlecast._tcp.local", "_airplay._tcp.local", "_raop._tcp.local", "_spotify-connect._tcp.local",
        "_ipp._tcp.local", "_ipps._tcp.local", "_printer._tcp.local", "_pdl-datastream._tcp.local",
        "_uscan._tcp.local", "_scanner._tcp.local", "_http._tcp.local", "_smb._tcp.local",
        "_afpovertcp._tcp.local", "_device-info._tcp.local", "_companion-link._tcp.local", "_hap._tcp.local",
        "_homekit._tcp.local", "_sonos._tcp.local", "_amzn-wplay._tcp.local", "_androidtvremote2._tcp.local",
        "_workstation._tcp.local", "_ssh._tcp.local", "_sftp-ssh._tcp.local", "_rfb._tcp.local",
        "_mqtt._tcp.local", "_esphomelib._tcp.local", "_home-assistant._tcp.local", "_matter._tcp.local",
        "_nvstream._tcp.local", "_daap._tcp.local", "_hue._tcp.local", "_miio._udp.local",
    )

    private class Mutable {
        var hostname: String? = null
        var preferred: String? = null
        var model: String? = null
        val services = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        fun freeze() = MdnsInfo(hostname, services, listOfNotNull(preferred) + labels.filter { it != preferred }, model)
    }

    private val hexish = Regex("^[0-9a-fA-F-]{12,}$")

    private fun cleanLabel(raw: String): String? {
        val label = raw.substringAfter('@').trim()
        if (label.isEmpty() || hexish.matches(label)) return null
        return label
    }

    suspend fun browse(context: Context, timeoutMs: Long = 3000): Map<String, MdnsInfo> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching {
            wifi?.createMulticastLock("netscope-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val result = HashMap<String, Mutable>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val group = InetAddress.getByName("224.0.0.251")
                val queried = HashSet(SERVICE_TYPES)
                fun send(types: List<String>) {
                    val q = DnsPacket.buildQuery(0, types.map { it to DnsPacket.TYPE_PTR })
                    socket.send(DatagramPacket(q, q.size, group, 5353))
                }
                SERVICE_TYPES.chunked(12).forEach { send(it) }
                SERVICE_TYPES.chunked(12).forEach { send(it) }
                val buf = ByteArray(9000)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val from = pkt.address?.hostAddress ?: continue
                    val records = runCatching { DnsPacket.parse(pkt.data, pkt.length) }.getOrNull() ?: continue
                    val entry = result.getOrPut(from) { Mutable() }
                    val newTypes = ArrayList<String>()
                    for (r in records) {
                        when (r) {
                            is DnsRecord.Ptr -> {
                                if (r.name.startsWith("_services._dns-sd")) {
                                    if (queried.add(r.target)) newTypes += r.target
                                    entry.services += r.target.removeSuffix(".local")
                                } else if (r.name.startsWith("_") && !r.name.endsWith("in-addr.arpa")) {
                                    entry.services += r.name.removeSuffix(".local")
                                    cleanLabel(r.target.removeSuffix("." + r.name))?.let { entry.labels += it }
                                }
                            }
                            is DnsRecord.A -> {
                                val e = result.getOrPut(r.address) { Mutable() }
                                if (e.hostname == null) e.hostname = r.name
                            }
                            is DnsRecord.Srv -> if (entry.hostname == null) entry.hostname = r.target
                            is DnsRecord.Txt -> r.entries.forEach { kv ->
                                when {
                                    kv.startsWith("fn=") -> entry.preferred = kv.substring(3).takeIf { it.isNotBlank() }
                                    kv.startsWith("md=") -> entry.model = entry.model ?: kv.substring(3)
                                    kv.startsWith("model=") -> entry.model = entry.model ?: kv.substring(6)
                                }
                            }
                            else -> Unit
                        }
                    }
                    if (newTypes.isNotEmpty()) runCatching { newTypes.chunked(12).forEach { send(it) } }
                }
            }
        } catch (e: Exception) {
            // Network unavailable: return whatever we collected.
        } finally {
            runCatching { lock?.release() }
        }
        result.filterValues { it.hostname != null || it.services.isNotEmpty() }.mapValues { it.value.freeze() }
    }

    /** Ask the host itself (port 5353) for its .local name. Works for Apple, Linux/avahi, many IoT devices. */
    suspend fun reverseLookup(ip: String, timeoutMs: Int = 1200): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val q = DnsPacket.buildQuery(0x2a2a, listOf(name to DnsPacket.TYPE_PTR))
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 5353))
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                DnsPacket.parse(p.data, p.length).filterIsInstance<DnsRecord.Ptr>()
                    .firstOrNull { it.name.endsWith("in-addr.arpa") }?.target
                    ?.removeSuffix(".")?.removeSuffix(".local")
            }
        }.getOrNull()
    }
}

data class NetBiosInfo(val name: String, val mac: String?)

/** NetBIOS node status (UDP 137): Windows / Samba machine names, and often the real MAC address. */
object NetBios {
    suspend fun query(ip: String, timeoutMs: Int = 1000): NetBiosInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = ByteArray(50)
            req[0] = 0x4E; req[1] = 0x53
            req[5] = 1
            req[12] = 0x20
            req[13] = 'C'.code.toByte()
            req[14] = 'K'.code.toByte()
            for (i in 15..44) req[i] = 'A'.code.toByte()
            req[45] = 0
            req[47] = 0x21
            req[49] = 0x01
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val len = p.length
                if (len < 57) return@use null
                val count = buf[56].toInt() and 0xFF
                var name: String? = null
                var off = 57
                repeat(count) {
                    if (off + 18 > len) return@repeat
                    val n = String(buf, off, 15, Charsets.US_ASCII).trim()
                    val suffix = buf[off + 15].toInt() and 0xFF
                    val flags = ((buf[off + 16].toInt() and 0xFF) shl 8) or (buf[off + 17].toInt() and 0xFF)
                    if (name == null && suffix == 0 && (flags and 0x8000) == 0 && n.isNotEmpty()) name = n
                    off += 18
                }
                var mac: String? = null
                if (off + 6 <= len) {
                    val bytes = (0 until 6).map { buf[off + it].toInt() and 0xFF }
                    if (bytes.any { it != 0 }) mac = bytes.joinToString(":") { "%02X".format(it) }
                }
                name?.let { NetBiosInfo(it, mac) }
            }
        }.getOrNull()
    }
}

/** UPnP / SSDP discovery: smart TVs, routers, media servers, consoles. */
object SsdpDiscovery {
    suspend fun discover(timeoutMs: Long = 3000): Map<String, UpnpDevice> = withContext(Dispatchers.IO) {
        val found = HashMap<String, Pair<String?, String?>>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 250
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: ssdp:all\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val ip = p.address?.hostAddress ?: continue
                    val headers = String(p.data, 0, p.length).lines().mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i > 0) line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim() else null
                    }.toMap()
                    val prev = found[ip]
                    if (prev == null || prev.first == null) found[ip] = headers["location"] to headers["server"]
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        coroutineScope {
            found.map { (ip, pair) -> async { ip to describe(pair.first, pair.second) } }.awaitAll().toMap()
        }
    }

    private suspend fun describe(location: String?, server: String?): UpnpDevice {
        if (location == null) return UpnpDevice(server = server)
        val xml = withTimeoutOrNull(2500) { runCatching { Http.get(location, 2000) }.getOrNull() }
            ?: return UpnpDevice(server = server, location = location)
        fun tag(t: String) = Regex("<$t>\\s*([^<]+?)\\s*</$t>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")
        return UpnpDevice(
            server = server,
            location = location,
            friendlyName = tag("friendlyName"),
            manufacturer = tag("manufacturer"),
            modelName = tag("modelName"),
            deviceType = tag("deviceType")?.substringAfter("device:")?.substringBefore(":"),
        )
    }
}

object ReverseDns {
    private val pool = java.util.concurrent.Executors.newCachedThreadPool()

    /** Blocking resolver calls can't be cancelled, so run them on a pool and stop waiting after the timeout. */
    suspend fun lookup(ip: String, timeoutMs: Long = 2500): String? = withContext(Dispatchers.IO) {
        val future = pool.submit<String?> {
            runCatching {
                InetAddress.getByName(ip).canonicalHostName?.takeIf { it != ip && !IpUtils.isValidIpv4(it) }
            }.getOrNull()
        }
        runCatching { future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
    }
}
