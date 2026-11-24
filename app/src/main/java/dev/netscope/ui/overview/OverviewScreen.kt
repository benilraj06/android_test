package dev.netscope.ui.overview

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netscope.container
import dev.netscope.net.ConnectionInfo
import dev.netscope.net.DnsTool
import dev.netscope.net.IpDetails
import dev.netscope.net.IpLookup
import dev.netscope.net.PingTool
import dev.netscope.net.Signal
import dev.netscope.net.WifiSecurity
import dev.netscope.ui.PermissionCard
import dev.netscope.ui.Routes
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.GradientCard
import dev.netscope.ui.components.IconBadge
import dev.netscope.ui.components.InfoRow
import dev.netscope.ui.components.Pill
import dev.netscope.ui.components.RingGauge
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.StatTile
import dev.netscope.ui.components.timeAgo
import dev.netscope.ui.rememberWifiPermissionState
import dev.netscope.ui.theme.NsColors
import dev.netscope.ui.theme.latencyColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    data class UiState(
        val connection: ConnectionInfo? = null,
        val security: WifiSecurity? = null,
        val publicIp: IpDetails? = null,
        val publicIpError: String? = null,
        val gatewayMs: Double? = null,
        val internetMs: Double? = null,
        val dnsMs: Long? = null,
        val checking: Boolean = false,
        val checkedAt: Long? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    val scan = c.scanController.state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshConnection()
                delay(3000)
            }
        }
        refreshChecks()
    }

    fun refreshConnection() {
        val conn = c.networkInfo.current()
        val sec = conn.bssid?.let { b -> c.wifiScanner.results().firstOrNull { it.bssid.equals(b, ignoreCase = true) }?.security }
        _state.update { it.copy(connection = conn, security = sec ?: it.security) }
    }

    fun refreshChecks() {
        if (_state.value.checking) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(checking = true) }
            val conn = c.networkInfo.current()
            coroutineScope {
                val gw = async { conn.gateway?.let { PingTool.quickLatency(it) } }
                val net = async { PingTool.quickLatency("1.1.1.1") }
                val dns = async { DnsTool.resolverLatency() }
                val pub = async { runCatching { IpLookup.lookup(null) } }
                val p = pub.await()
                val g = gw.await()
                val n = net.await()
                val d = dns.await()
                _state.update {
                    it.copy(
                        gatewayMs = g, internetMs = n, dnsMs = d,
                        publicIp = p.getOrNull() ?: it.publicIp,
                        publicIpError = p.exceptionOrNull()?.let { e -> e.message ?: "Offline" },
                        checking = false, checkedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }
}

@Composable
fun OverviewScreen(
    onOpenSettings: () -> Unit,
    onNavigateTab: (String) -> Unit,
    onOpenTool: (String, String?) -> Unit,
    onOpenDevice: (String) -> Unit,
    vm: OverviewViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val perm = rememberWifiPermissionState(onGranted = { vm.refreshChecks() })
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val conn = state.connection
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("NetScope") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroCard(conn) }
            if (!perm.ready && conn?.isWifi == true) {
                item { PermissionCard(perm) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val online = scan.online.size
                    StatTile(
                        icon = Icons.Filled.Devices,
                        label = "Devices",
                        value = if (scan.lastScan == null) "Scan" else "$online online",
                        sub = if (scan.running) "Scanning…" else "Last scan ${timeAgo(scan.lastScan)}",
                        tint = NsColors.Info,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!scan.running && scan.lastScan == null) context.container.scanController.start()
                            onNavigateTab(Routes.DEVICES)
                        },
                    )
                    StatTile(
                        icon = Icons.Filled.Public,
                        label = "Public IP",
                        value = state.publicIp?.ip ?: if (state.checking) "…" else "Unavailable",
                        sub = state.publicIp?.let { listOfNotNull(it.flag.ifEmpty { null }, it.isp ?: it.org).joinToString(" ") }
                            ?: state.publicIpError,
                        tint = NsColors.Violet,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenTool("iplookup", null) },
                    )
                }
            }
            item { LatencyCard(state, onRefresh = vm::refreshChecks) }
            if (conn?.isWifi == true) {
                item { SecurityCard(state.security, conn) }
            }
            item {
                SectionHeader("Quick actions")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickAction(Icons.Filled.Devices, "Scan network", NsColors.Info, Modifier.weight(1f)) {
                        context.container.scanController.start()
                        onNavigateTab(Routes.DEVICES)
                    }
                    QuickAction(Icons.Filled.Speed, "Speed test", NsColors.Excellent, Modifier.weight(1f)) { onOpenTool("speedtest", null) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickAction(Icons.Filled.Wifi, "Wi-Fi analyzer", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onNavigateTab(Routes.WIFI) }
                    QuickAction(Icons.Filled.Router, "Router ports", NsColors.Fair, Modifier.weight(1f)) {
                        val gw = conn?.gateway
                        if (gw != null) {
                            context.container.scanController.ensureDevice(gw, isGateway = true)
                            onOpenDevice(gw)
                        }
                    }
                }
            }
            if (conn?.isWifi == true) {
                item { ConnectionDetails(conn) }
            }
        }
    }
}

@Composable
private fun HeroCard(conn: ConnectionInfo?) {
    GradientCard(Modifier.fillMaxWidth()) {
        if (conn == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GradientCard
        }
        if (!conn.isWifi) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiOff, null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Not on Wi-Fi", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (conn.hasAnyNetwork) "You're online via mobile data or another network. Device scanning needs Wi-Fi."
                        else "No network connection.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@GradientCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CONNECTED TO", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    conn.ssid ?: "Wi-Fi network",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    conn.band?.let { Pill(it.label, MaterialTheme.colorScheme.onPrimaryContainer) }
                    conn.channel?.let { Pill("Ch $it", MaterialTheme.colorScheme.onPrimaryContainer) }
                    conn.linkSpeedMbps?.let { Pill("$it Mbps", MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
            val rssi = conn.rssi
            RingGauge(
                fraction = (conn.signalPercent ?: 0) / 100f,
                modifier = Modifier.size(96.dp),
                strokeWidth = 9.dp,
                trackColor = Color.White.copy(alpha = 0.15f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${conn.signalPercent ?: "–"}%", style = MaterialTheme.typography.titleLarge)
                    Text(rssi?.let { Signal.quality(it) } ?: "", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroStat("IP address", conn.ipv4 ?: "—", Modifier.weight(1f))
            HeroStat("Gateway", conn.gateway ?: "—", Modifier.weight(1f))
            HeroStat("Signal", conn.rssi?.let { "$it dBm" } ?: "—", Modifier.weight(0.8f))
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LatencyCard(state: OverviewViewModel.UiState, onRefresh: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.NetworkCheck, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Connection health", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.checking) "Measuring…" else "Checked ${timeAgo(state.checkedAt)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            LatencyStat("Router", state.gatewayMs, Modifier.weight(1f))
            LatencyStat("Internet", state.internetMs, Modifier.weight(1f))
            LatencyStat("DNS", state.dnsMs?.toDouble(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun LatencyStat(label: String, ms: Double?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ms?.let { "%.0f".format(it) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            color = if (ms == null) MaterialTheme.colorScheme.onSurfaceVariant else latencyColor(ms),
        )
        Text(if (ms != null) "ms · $label" else label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SecurityCard(security: WifiSecurity?, conn: ConnectionInfo) {
    val (icon: ImageVector, color: Color, title: String, text: String) = when {
        security == null -> Quad(Icons.Filled.Lock, MaterialTheme.colorScheme.onSurfaceVariant, "Security unknown", "Open the Wi-Fi tab to scan nearby networks and detect your encryption.")
        security.weak -> Quad(Icons.Filled.LockOpen, NsColors.Weak, "${security.label} — weak protection", "Traffic on this network can be intercepted. Switch your router to WPA2 or WPA3.")
        security == WifiSecurity.WPA3 -> Quad(Icons.Filled.CheckCircle, NsColors.Excellent, "WPA3 — excellent", "Your network uses the latest Wi-Fi encryption.")
        else -> Quad(Icons.Filled.Lock, NsColors.Good, "${security.label} — good", "Your network is encrypted. Enable WPA3 on your router if supported.")
    }
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (conn.routerVendor != null) {
            Spacer(Modifier.height(8.dp))
            Text("Router made by ${conn.routerVendor}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Quad(val a: ImageVector, val b: Color, val c: String, val d: String)

@Composable
private fun QuickAction(icon: ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConnectionDetails(conn: ConnectionInfo) {
    Column {
        SectionHeader("Connection details")
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            InfoRow("IPv4", conn.ipv4, mono = true)
            InfoRow("Subnet mask", conn.subnetMask?.let { "$it (/${conn.prefixLength})" }, mono = true)
            InfoRow("Gateway", conn.gateway, mono = true)
            InfoRow("DNS servers", conn.dns.joinToString("\n").ifEmpty { null }, mono = true)
            InfoRow("DHCP server", conn.dhcpServer, mono = true)
            InfoRow("IPv6", conn.ipv6.joinToString("\n").ifEmpty { null }, mono = true)
            InfoRow("BSSID", conn.bssid, mono = true)
            InfoRow("Router vendor", conn.routerVendor)
            InfoRow("Standard", conn.wifiStandard)
            InfoRow("Frequency", conn.frequency?.let { "$it MHz" })
            InfoRow("Link speed", listOfNotNull(conn.txSpeedMbps?.let { "↑ $it" }, conn.rxSpeedMbps?.let { "↓ $it" }).joinToString("  ").ifEmpty { conn.linkSpeedMbps?.toString() }?.let { "$it Mbps" })
            InfoRow("Interface", conn.interfaceName)
        }
    }
}
