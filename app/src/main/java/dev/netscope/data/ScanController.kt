package dev.netscope.data

import android.content.Context
import dev.netscope.net.Arp
import dev.netscope.net.ConnectionInfo
import dev.netscope.net.DeviceClassifier
import dev.netscope.net.IpUtils
import dev.netscope.net.MdnsDiscovery
import dev.netscope.net.MdnsInfo
import dev.netscope.net.NetBios
import dev.netscope.net.NetDevice
import dev.netscope.net.NetworkInfoProvider
import dev.netscope.net.OuiDatabase
import dev.netscope.net.ReverseDns
import dev.netscope.net.SsdpDiscovery
import dev.netscope.net.SubnetScanner
import dev.netscope.net.SweepEvent
import dev.netscope.net.UpnpDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val progress: Float = 0f,
    val devices: List<NetDevice> = emptyList(),
    val lastScan: Long? = null,
    val fromCache: Boolean = false,
    val subnet: String? = null,
    val networkId: String? = null,
    val ssid: String? = null,
    val error: String? = null,
    val isolatedHint: Boolean = false,
    val durationMs: Long? = null,
) {
    val online: List<NetDevice> get() = devices.filter { it.online }
    val offline: List<NetDevice> get() = devices.filter { !it.online }
}

data class ScanOutcome(val networkId: String, val ssid: String?, val devices: List<NetDevice>, val newDevices: List<NetDevice>)

val deviceOrder: Comparator<NetDevice> = compareByDescending<NetDevice> { it.online }
    .thenByDescending { it.isGateway }
    .thenByDescending { it.isSelf }
    .thenBy { IpUtils.ipToLong(it.ip) }

/** Runs network scans (UI or background), merges discovery sources and persists results. */
class ScanController(
    private val context: Context,
    private val info: NetworkInfoProvider,
    private val repo: DeviceRepository,
    private val oui: OuiDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("netscope_networks", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch {
            try {
                performScan(ui = true)
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, phase = "Stopped") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: "Scan failed") }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** SSID needs location permission; fall back to the last SSID seen with this gateway. */
    fun networkIdFor(conn: ConnectionInfo): String? {
        val gw = conn.gateway
        val ssid = conn.ssid
        if (gw == null) return ssid
        return if (ssid != null) {
            prefs.edit().putString("gw_$gw", ssid).apply()
            ssid
        } else {
            prefs.getString("gw_$gw", null) ?: "gw:$gw"
        }
    }

    suspend fun loadCached() {
        if (_state.value.devices.isNotEmpty() || isRunning) return
        val conn = withContext(Dispatchers.IO) { info.current() }
        val id = networkIdFor(conn) ?: return
        val stored = repo.list(id)
        if (stored.isEmpty()) return
        _state.update {
            it.copy(
                devices = stored.map { e -> e.toNetDevice(online = false) }.sortedWith(deviceOrder),
                networkId = id,
                ssid = conn.ssid,
                lastScan = stored.maxOf { e -> e.lastSeen },
                fromCache = true,
            )
        }
    }

    fun patch(ip: String, transform: (NetDevice) -> NetDevice) {
        _state.update { s -> s.copy(devices = s.devices.map { if (it.ip == ip) transform(it) else it }) }
    }

    fun ensureDevice(ip: String, isGateway: Boolean = false) {
        if (_state.value.devices.any { it.ip == ip }) return
        _state.update { s ->
            s.copy(devices = (s.devices + NetDevice(ip = ip, isGateway = isGateway, key = if (isGateway) "gateway" else "ip:$ip").let {
                it.copy(type = DeviceClassifier.classify(it))
            }).sortedWith(deviceOrder))
        }
    }

    fun remove(ip: String) {
        _state.update { s -> s.copy(devices = s.devices.filterNot { it.ip == ip }) }
    }

    suspend fun performScan(ui: Boolean): ScanOutcome? = mutex.withLock {
        val started = System.currentTimeMillis()
        val conn = withContext(Dispatchers.IO) { info.current() }
        val ip = conn.ipv4
        if (!conn.isWifi || ip == null) {
            if (ui) _state.update { it.copy(running = false, error = "Connect to a Wi-Fi network to scan for devices.") }
            return@withLock null
        }
        val networkId = networkIdFor(conn) ?: "default"
        val range = IpUtils.scanRange(ip, conn.prefixLength ?: 24)
        val hostSet = range.hosts.toHashSet()
        val devices = ConcurrentHashMap<String, NetDevice>()
        var lastPublish = 0L
        var sweepFraction = 0f

        fun publish(phase: String, progress: Float, force: Boolean = false) {
            if (!ui) return
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < 120) return
            lastPublish = now
            _state.update {
                it.copy(
                    running = true, phase = phase, progress = progress,
                    devices = devices.values.sortedWith(deviceOrder),
                    subnet = range.cidr, networkId = networkId, ssid = conn.ssid,
                    error = null, isolatedHint = false, fromCache = false,
                )
            }
        }

        devices[ip] = NetDevice(ip = ip, isSelf = true, latencyMs = 0)
        conn.gateway?.let { gw -> devices[gw] = NetDevice(ip = gw, isGateway = true, vendor = conn.routerVendor) }
        publish("Starting…", 0f, force = true)

        coroutineScope {
            val mdnsJob = async { MdnsDiscovery.browse(context, 3500) }
            val ssdpJob = async { SsdpDiscovery.discover(3500) }

            SubnetScanner.sweep(range.hosts).collect { ev ->
                when (ev) {
                    is SweepEvent.Alive -> {
                        devices.compute(ev.ip) { _, old ->
                            (old ?: NetDevice(ip = ev.ip)).copy(latencyMs = ev.latencyMs, probePorts = ev.openPorts)
                        }
                        publish("Scanning ${range.cidr}", 0.75f * sweepFraction, force = true)
                    }
                    is SweepEvent.Progress -> {
                        sweepFraction = ev.done.toFloat() / ev.total
                        publish("Scanning ${range.cidr}", 0.75f * sweepFraction, force = ev.done == ev.total)
                    }
                }
            }

            publish("Listening for services…", 0.78f, force = true)
            val mdns: Map<String, MdnsInfo> = runCatching { mdnsJob.await() }.getOrDefault(emptyMap())
            val ssdp: Map<String, UpnpDevice> = runCatching { ssdpJob.await() }.getOrDefault(emptyMap())
            mdns.forEach { (addr, m) ->
                if (addr in hostSet || addr == ip) {
                    devices.compute(addr) { _, old ->
                        val base = old ?: NetDevice(ip = addr)
                        base.copy(
                            mdnsName = base.mdnsName ?: m.hostname,
                            services = base.services + m.services,
                            serviceLabels = (base.serviceLabels + m.labels).distinct(),
                            model = base.model ?: m.model,
                        )
                    }
                }
            }
            ssdp.forEach { (addr, u) ->
                if (addr in hostSet || addr == ip) {
                    devices.compute(addr) { _, old -> (old ?: NetDevice(ip = addr)).copy(upnp = u) }
                }
            }

            publish("Resolving names…", 0.8f, force = true)
            val targets = devices.keys.toList()
            val done = AtomicInteger()
            val gate = Semaphore(16)
            targets.map { addr ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val rdns = async { ReverseDns.lookup(addr) }
                        val local = async { if (addr == ip) null else MdnsDiscovery.reverseLookup(addr) }
                        val nb = async { if (addr == ip) null else NetBios.query(addr) }
                        val r = rdns.await()
                        val l = local.await()
                        val n = nb.await()
                        devices.compute(addr) { _, old ->
                            old?.copy(
                                hostname = r ?: old.hostname,
                                mdnsName = old.mdnsName ?: l,
                                netbiosName = n?.name ?: old.netbiosName,
                                mac = old.mac ?: n?.mac,
                            )
                        }
                        publish("Resolving names…", 0.8f + 0.18f * done.incrementAndGet() / targets.size)
                    }
                }
            }.awaitAll()
        }

        // MAC addresses (only readable on Android 9 and below) and vendors.
        val arp = withContext(Dispatchers.IO) { Arp.read() }
        val now = System.currentTimeMillis()
        val stored = repo.list(networkId).associateBy { it.deviceKey }
        val hadBaseline = stored.isNotEmpty()

        val enriched = devices.values.map { d ->
            val mac = d.mac ?: if (d.isSelf) null else arp[d.ip]
            val vendor = withContext(Dispatchers.IO) { oui.lookup(mac) } ?: d.vendor
            val withMac = d.copy(mac = mac, vendor = vendor)
            withMac.copy(type = DeviceClassifier.classify(withMac))
        }
        // Keys must be unique per scan; fall back to IP-qualified keys on collisions.
        val keyed = enriched.groupBy { it.computeKey() }.flatMap { (key, group) ->
            if (group.size == 1) listOf(group.first().copy(key = key)) else group.map { it.copy(key = "$key@${it.ip}") }
        }
        val online = keyed.map { d ->
            val s = stored[d.key]
            d.copy(
                customName = s?.customName,
                notes = s?.notes,
                isKnown = s?.isKnown ?: false,
                firstSeen = s?.firstSeen ?: now,
                lastSeen = now,
                isNew = hadBaseline && s == null,
                openPorts = s?.openPorts.toPortList(),
                lastPortScan = s?.lastPortScan,
                online = true,
            )
        }
        val onlineKeys = online.map { it.key }.toHashSet()
        val offline = stored.values.filter { it.deviceKey !in onlineKeys }.map { it.toNetDevice(online = false) }

        repo.save(networkId, online)
        val duration = now - started
        repo.recordScan(ScanEntity(networkId = networkId, timestamp = now, deviceCount = online.size, newCount = online.count { it.isNew }, durationMs = duration))

        val othersFound = online.count { !it.isSelf && !it.isGateway }
        if (ui) {
            _state.value = ScanState(
                running = false,
                phase = "Done",
                progress = 1f,
                devices = (online + offline).sortedWith(deviceOrder),
                lastScan = now,
                subnet = range.cidr,
                networkId = networkId,
                ssid = conn.ssid,
                isolatedHint = othersFound == 0,
                durationMs = duration,
            )
        }
        ScanOutcome(networkId, conn.ssid, online, online.filter { it.isNew })
    }
}
