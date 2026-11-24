package dev.netscope.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

object PingTool {
    private val timeRegex = Regex("time[=<]([0-9.]+) ?ms")
    private val fromRegex = Regex("[Ff]rom ([0-9a-fA-F.:]+)")
    private val lossRegex = Regex("([0-9.]+)% packet loss")

    fun parseTime(line: String): Double? = timeRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
    fun parseLoss(line: String): Double? = lossRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Streams output of the system ping binary. count <= 0 means run until cancelled. */
    fun ping(host: String, count: Int = 4, timeoutSec: Int = 2): Flow<String> = flow {
        val cmd = buildList {
            add("/system/bin/ping")
            if (count > 0) { add("-c"); add(count.toString()) }
            add("-W"); add(timeoutSec.toString())
            add(host)
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        } finally {
            process.destroy()
        }
    }.flowOn(Dispatchers.IO)

    /** Average RTT in ms using ICMP, falling back to a TCP connect. */
    suspend fun quickLatency(host: String): Double? = withContext(Dispatchers.IO) {
        val icmp = runCatching {
            val p = ProcessBuilder("/system/bin/ping", "-c", "3", "-i", "0.2", "-W", "1", host)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.lines().mapNotNull { parseTime(it) }.takeIf { it.isNotEmpty() }?.average()
        }.getOrNull()
        icmp ?: tcpLatency(host)
    }

    fun tcpLatency(host: String, ports: List<Int> = listOf(443, 80, 53)): Double? {
        for (port in ports) {
            val t0 = System.nanoTime()
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1500) } }.isSuccess
            if (ok) return (System.nanoTime() - t0) / 1_000_000.0
        }
        return null
    }

    data class Hop(val ttl: Int, val ip: String?, val hostname: String?, val timeMs: Double?, val reached: Boolean)

    /** Traceroute built on `ping -t TTL` (no root needed). */
    fun traceroute(host: String, maxHops: Int = 30): Flow<Hop> = flow {
        val target = InetAddress.getByName(host).hostAddress ?: throw IOException("Cannot resolve $host")
        for (ttl in 1..maxHops) {
            val t0 = System.nanoTime()
            val output = runCatching {
                val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", "-t", ttl.toString(), target)
                    .redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                text
            }.getOrDefault("")
            val wall = (System.nanoTime() - t0) / 1_000_000.0
            val replyLine = output.lines().firstOrNull { it.contains("bytes from") }
            val exceededLine = output.lines().firstOrNull { it.contains("From ") }
            val hop = when {
                replyLine != null -> Hop(ttl, target, null, parseTime(replyLine), true)
                exceededLine != null -> Hop(ttl, fromRegex.find(exceededLine)?.groupValues?.get(1), null, wall, false)
                else -> Hop(ttl, null, null, null, false)
            }
            val name = hop.ip?.let { ReverseDns.lookup(it, 1500) }
            emit(hop.copy(hostname = name))
            if (hop.reached) break
        }
    }.flowOn(Dispatchers.IO)
}

data class DnsAnswer(val name: String, val type: String, val ttl: Int, val data: String)

object DnsTool {
    val TYPES = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "PTR", "CAA")
    private val typeNames = mapOf(
        1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 12 to "PTR", 15 to "MX", 16 to "TXT",
        28 to "AAAA", 33 to "SRV", 65 to "HTTPS", 257 to "CAA",
    )

    /** DNS-over-HTTPS (Cloudflare) so every record type works without root. Returns answers + time. */
    suspend fun query(name: String, type: String): Pair<List<DnsAnswer>, Long> = withContext(Dispatchers.IO) {
        val qName = if (type == "PTR" && IpUtils.isValidIpv4(name)) {
            name.trim().split('.').reversed().joinToString(".") + ".in-addr.arpa"
        } else name.trim()
        val t0 = System.nanoTime()
        val body = Http.get(
            "https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(qName, "UTF-8")}&type=$type",
            headers = mapOf("Accept" to "application/dns-json"),
        )
        val ms = elapsedMs(t0)
        val json = JSONObject(body)
        val status = json.optInt("Status")
        val arr = json.optJSONArray("Answer")
        val list = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            DnsAnswer(
                name = o.optString("name"),
                type = typeNames[o.optInt("type")] ?: o.optInt("type").toString(),
                ttl = o.optInt("TTL"),
                data = o.optString("data"),
            )
        }
        if (status != 0 && list.isEmpty()) {
            throw IOException(
                when (status) {
                    2 -> "Server failure (SERVFAIL)"
                    3 -> "Domain does not exist (NXDOMAIN)"
                    5 -> "Query refused"
                    else -> "DNS error $status"
                },
            )
        }
        list to ms
    }

    suspend fun systemResolve(name: String): Pair<List<String>, Long> = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val list = InetAddress.getAllByName(name.trim()).mapNotNull { it.hostAddress }
        list to elapsedMs(t0)
    }

    /** Time a lookup of a random name to measure the resolver's round trip. */
    suspend fun resolverLatency(): Long = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        runCatching { InetAddress.getAllByName("${UUID.randomUUID().toString().take(8)}.example.com") }
        elapsedMs(t0)
    }
}

data class IpDetails(
    val ip: String,
    val type: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val countryCode: String?,
    val continent: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isp: String?,
    val org: String?,
    val asn: String?,
    val domain: String?,
    val timezone: String?,
) {
    val flag: String
        get() = countryCode?.takeIf { it.length == 2 }?.uppercase()
            ?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("") ?: ""
    val location: String get() = listOfNotNull(city, region, country).distinct().joinToString(", ")
}

object IpLookup {
    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    suspend fun lookup(ip: String?): IpDetails = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get("https://ipwho.is/${ip?.trim().orEmpty()}"))
        if (!json.optBoolean("success", true)) throw IOException(json.str("message") ?: "Lookup failed")
        val conn = json.optJSONObject("connection")
        val tz = json.optJSONObject("timezone")
        IpDetails(
            ip = json.str("ip") ?: ip.orEmpty(),
            type = json.str("type"),
            city = json.str("city"),
            region = json.str("region"),
            country = json.str("country"),
            countryCode = json.str("country_code"),
            continent = json.str("continent"),
            latitude = json.optDouble("latitude").takeIf { !it.isNaN() },
            longitude = json.optDouble("longitude").takeIf { !it.isNaN() },
            isp = conn?.str("isp"),
            org = conn?.str("org"),
            asn = conn?.optLong("asn")?.takeIf { it > 0 }?.let { "AS$it" },
            domain = conn?.str("domain"),
            timezone = tz?.str("id"),
        )
    }
}

object WakeOnLan {
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    suspend fun send(mac: String, broadcast: String, port: Int = 9) = withContext(Dispatchers.IO) {
        val m = parseMac(mac) ?: throw IllegalArgumentException("Invalid MAC address")
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(m, 0, packet, i * 6, 6)
        DatagramSocket().use { s ->
            s.broadcast = true
            val addr = InetAddress.getByName(broadcast)
            repeat(3) { s.send(DatagramPacket(packet, packet.size, addr, port)) }
        }
    }
}

enum class SpeedPhase(val label: String) { IDLE("Ready"), PING("Latency"), DOWNLOAD("Download"), UPLOAD("Upload"), DONE("Done"), ERROR("Error") }

data class SpeedState(
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val liveMbps: Double = 0.0,
    val error: String? = null,
)

/** Speed test against Cloudflare's public speed endpoints. */
object SpeedTest {
    private const val BASE = "https://speed.cloudflare.com"

    fun run(): Flow<SpeedState> = flow {
        var s = SpeedState(phase = SpeedPhase.PING)
        emit(s)
        val pings = (1..8).mapNotNull { runCatching { ttfb("$BASE/__down?bytes=0") }.getOrNull() }.drop(1)
        if (pings.isEmpty()) throw IOException("Cannot reach speed.cloudflare.com")
        val sorted = pings.sorted()
        val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> abs(a - b) }.average() else 0.0
        s = s.copy(pingMs = sorted[sorted.size / 2], jitterMs = jitter, phase = SpeedPhase.DOWNLOAD)
        emit(s)

        // Download
        run {
            val c = URL("$BASE/__down?bytes=100000000").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            try {
                val input = c.inputStream
                val buf = ByteArray(64 * 1024)
                var bytes = 0L
                val start = System.nanoTime()
                var lastEmit = start
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val now = System.nanoTime()
                    val secs = (now - start) / 1e9
                    if (now - lastEmit > 200_000_000L) {
                        lastEmit = now
                        s = s.copy(liveMbps = bytes * 8 / secs / 1e6)
                        emit(s)
                    }
                    if (secs > 10) break
                }
                val secs = (System.nanoTime() - start) / 1e9
                s = s.copy(downloadMbps = bytes * 8 / secs / 1e6, liveMbps = 0.0, phase = SpeedPhase.UPLOAD)
                emit(s)
            } finally {
                c.disconnect()
            }
        }

        // Upload
        run {
            val total = 25 * 1024 * 1024
            val c = URL("$BASE/__up").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.doOutput = true
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setFixedLengthStreamingMode(total)
            try {
                val chunk = ByteArray(64 * 1024).also { Random.nextBytes(it) }
                val out = c.outputStream
                var sent = 0
                val start = System.nanoTime()
                var lastEmit = start
                while (sent < total) {
                    val n = minOf(chunk.size, total - sent)
                    out.write(chunk, 0, n)
                    sent += n
                    val now = System.nanoTime()
                    if (now - lastEmit > 200_000_000L) {
                        lastEmit = now
                        s = s.copy(liveMbps = sent * 8 / ((now - start) / 1e9) / 1e6)
                        emit(s)
                    }
                }
                out.flush()
                out.close()
                c.responseCode
                val secs = (System.nanoTime() - start) / 1e9
                s = s.copy(uploadMbps = sent * 8 / secs / 1e6, liveMbps = 0.0, phase = SpeedPhase.DONE)
                emit(s)
            } finally {
                c.disconnect()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun ttfb(url: String): Double {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("User-Agent", Http.USER_AGENT)
        try {
            val t0 = System.nanoTime()
            c.responseCode
            val ms = (System.nanoTime() - t0) / 1e6
            c.inputStream.use { it.readBytes() }
            return ms
        } finally {
            c.disconnect()
        }
    }
}
