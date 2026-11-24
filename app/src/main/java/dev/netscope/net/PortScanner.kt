package dev.netscope.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class OpenPort(
    val port: Int,
    val service: String,
    val banner: String?,
    val latencyMs: Long,
    val risk: Risk?,
    val riskNote: String?,
)

sealed interface PortScanEvent {
    data class Progress(val scanned: Int, val total: Int) : PortScanEvent
    data class Found(val port: OpenPort) : PortScanEvent
}

/** TCP connect scanner with banner grabbing (HTTP Server header, TLS certificate, text greetings). */
class PortScanner {

    fun scan(
        host: String,
        ports: List<Int>,
        timeoutMs: Int,
        concurrency: Int = 192,
        grabBanners: Boolean = true,
    ): Flow<PortScanEvent> = channelFlow {
        val gate = Semaphore(concurrency)
        val counter = AtomicInteger()
        val total = ports.size
        val step = (total / 200).coerceAtLeast(1)
        ports.forEach { port ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    probe(host, port, timeoutMs, grabBanners)?.let { send(PortScanEvent.Found(it)) }
                    val n = counter.incrementAndGet()
                    if (n % step == 0 || n == total) send(PortScanEvent.Progress(n, total))
                }
            }
        }
    }

    private fun probe(host: String, port: Int, timeoutMs: Int, grab: Boolean): OpenPort? {
        val t0 = System.nanoTime()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            runCatching { socket.close() }
            return null
        }
        val latency = elapsedMs(t0)
        val banner = if (grab) runCatching { grabBanner(socket, host, port) }.getOrNull() else null
        runCatching { socket.close() }
        val risk = Ports.risk(port)
        return OpenPort(port, Ports.name(port), banner, latency, risk?.first, risk?.second)
    }

    private fun grabBanner(socket: Socket, host: String, port: Int): String? {
        socket.soTimeout = 1500
        if (port in Ports.tlsPorts) return tlsInfo(socket, host, port)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        if (port in Ports.httpPorts) {
            out.write("HEAD / HTTP/1.0\r\nHost: $host\r\nUser-Agent: ${Http.USER_AGENT}\r\n\r\n".toByteArray())
            out.flush()
        }
        val buf = ByteArray(1024)
        val n = runCatching { input.read(buf) }.getOrDefault(-1)
        if (n <= 0) return null
        val text = String(buf, 0, n, Charsets.ISO_8859_1)
        if (text.startsWith("HTTP/")) {
            val lines = text.lines()
            val server = lines.firstOrNull { it.startsWith("Server:", ignoreCase = true) }?.substringAfter(':')?.trim()
            val status = lines.first().trim()
            return listOfNotNull(server, status).joinToString(" · ").take(140)
        }
        return text.map { if (it in ' '..'~') it else ' ' }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().take(140).takeIf { it.length >= 3 }
    }

    private fun tlsInfo(socket: Socket, host: String, port: Int): String? {
        // Trust-all is only used to READ the certificate for display; no data is sent.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val ssl = ctx.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = 2000
        ssl.startHandshake()
        val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
        val cn = cert?.subjectX500Principal?.name?.split(',')?.firstOrNull { it.trim().startsWith("CN=") }
            ?.substringAfter("CN=")
        val proto = ssl.session.protocol
        runCatching { ssl.close() }
        return listOfNotNull(proto, cn?.let { "CN=$it" }).joinToString(" · ").ifEmpty { null }
    }
}
