package dev.netscope.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

sealed interface SweepEvent {
    data class Progress(val done: Int, val total: Int) : SweepEvent
    data class Alive(val ip: String, val latencyMs: Long, val openPorts: Set<Int>) : SweepEvent
}

/**
 * Finds live hosts without root: a TCP connect that succeeds OR is actively refused (RST)
 * proves a host exists. InetAddress.isReachable is used as an extra signal.
 */
object SubnetScanner {
    private val PROBE_PORTS = listOf(80, 443, 22, 445, 139, 53, 62078, 8080, 554, 7000, 8008, 9100)

    fun sweep(hosts: List<String>, timeoutMs: Int = 600, concurrency: Int = 32): Flow<SweepEvent> = channelFlow {
        val gate = Semaphore(concurrency)
        val done = AtomicInteger()
        hosts.forEach { ip ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    probe(ip, timeoutMs)?.let { send(it) }
                    send(SweepEvent.Progress(done.incrementAndGet(), hosts.size))
                }
            }
        }
    }

    suspend fun probe(ip: String, timeoutMs: Int): SweepEvent.Alive? = coroutineScope {
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return@coroutineScope null
        val tcp = PROBE_PORTS.map { port -> async(Dispatchers.IO) { port to tcpProbe(addr, port, timeoutMs) } }
        val icmp = async(Dispatchers.IO) {
            val t0 = System.nanoTime()
            if (runCatching { addr.isReachable(timeoutMs) }.getOrDefault(false)) elapsedMs(t0) else null
        }
        val results = tcp.awaitAll()
        val icmpMs = icmp.await()
        val latencies = results.mapNotNull { it.second?.latency } + listOfNotNull(icmpMs)
        val latency = latencies.minOrNull() ?: return@coroutineScope null
        val open = results.filter { it.second?.open == true }.map { it.first }.toSet()
        SweepEvent.Alive(ip, latency, open)
    }

    private class TcpResult(val latency: Long, val open: Boolean)

    private fun tcpProbe(addr: InetAddress, port: Int, timeoutMs: Int): TcpResult? {
        val t0 = System.nanoTime()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(addr, port), timeoutMs)
            TcpResult(elapsedMs(t0), true)
        } catch (e: ConnectException) {
            val msg = e.message.orEmpty()
            if (msg.contains("ECONNREFUSED") || msg.contains("refused", ignoreCase = true)) TcpResult(elapsedMs(t0), false) else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}

/** /proc/net/arp is only readable before Android 10, but it costs nothing to try. */
object Arp {
    fun read(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to p[3].uppercase() else null
        }.toMap()
    }.getOrDefault(emptyMap())
}
