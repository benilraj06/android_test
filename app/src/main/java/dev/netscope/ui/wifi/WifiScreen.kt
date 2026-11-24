package dev.netscope.ui.wifi

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netscope.container
import dev.netscope.net.ChannelUtils
import dev.netscope.net.ConnectionInfo
import dev.netscope.net.Signal
import dev.netscope.net.WifiBand
import dev.netscope.net.WifiNetwork
import dev.netscope.ui.PermissionCard
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.ChannelGraph
import dev.netscope.ui.components.EmptyState
import dev.netscope.ui.components.InfoRow
import dev.netscope.ui.components.Pill
import dev.netscope.ui.components.RingGauge
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.SignalBars
import dev.netscope.ui.components.Sparkline
import dev.netscope.ui.components.timeAgo
import dev.netscope.ui.rememberWifiPermissionState
import dev.netscope.ui.theme.NsColors
import dev.netscope.ui.theme.signalColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WifiSort(val label: String) { SIGNAL("Signal"), NAME("Name"), CHANNEL("Channel") }

class WifiViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    data class UiState(
        val networks: List<WifiNetwork> = emptyList(),
        val connection: ConnectionInfo? = null,
        val lastUpdated: Long? = null,
        val scanning: Boolean = false,
        val throttled: Boolean = false,
        val rssiHistory: List<Int> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            c.wifiScanner.updates().collect { list ->
                _state.update { it.copy(networks = list, lastUpdated = System.currentTimeMillis(), scanning = false) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val conn = c.networkInfo.current()
                _state.update { s ->
                    val hist = conn.rssi?.let { (s.rssiHistory + it).takeLast(120) } ?: s.rssiHistory
                    s.copy(connection = conn, rssiHistory = hist)
                }
                delay(1000)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = c.wifiScanner.results()
            _state.update {
                it.copy(
                    networks = cached.ifEmpty { it.networks },
                    lastUpdated = if (cached.isNotEmpty() && it.lastUpdated == null) System.currentTimeMillis() else it.lastUpdated,
                    scanning = true,
                )
            }
            val ok = c.wifiScanner.startScan()
            _state.update { it.copy(scanning = ok, throttled = !ok) }
            if (ok) {
                delay(8000)
                _state.update { it.copy(scanning = false) }
            }
        }
    }
}

@Composable
fun WifiScreen(vm: WifiViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val perm = rememberWifiPermissionState(onGranted = { vm.refresh() })
    var tab by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<WifiNetwork?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Wi-Fi") },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = perm.ready) {
                        Icon(
                            Icons.Filled.Refresh, contentDescription = "Rescan",
                            modifier = if (state.scanning) Modifier.rotate(angle) else Modifier,
                        )
                    }
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
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Networks", "Channels", "Signal").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = tab == i,
                            onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(label) }
                    }
                }
            }
            if (!perm.ready && tab != 2) {
                item { PermissionCard(perm) }
            } else {
                when (tab) {
                    0 -> networksTab(state, onSelect = { selected = it })
                    1 -> channelsTab(state)
                    else -> signalTab(state)
                }
            }
        }
    }

    selected?.let { n ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            NetworkDetails(n, connected = n.bssid.equals(state.connection?.bssid, ignoreCase = true))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.networksTab(
    state: WifiViewModel.UiState,
    onSelect: (WifiNetwork) -> Unit,
) {
    item(key = "filters") { NetworkFilters() }
    val filter = NetworkFilterState.band
    val sort = NetworkFilterState.sort
    val list = state.networks
        .filter { filter == null || it.band == filter }
        .let { l ->
            when (sort) {
                WifiSort.SIGNAL -> l.sortedByDescending { it.level }
                WifiSort.NAME -> l.sortedBy { it.displaySsid.lowercase() }
                WifiSort.CHANNEL -> l.sortedBy { it.channel }
            }
        }
    item(key = "summary") {
        Column {
            Text(
                "${list.size} networks · updated ${timeAgo(state.lastUpdated)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.throttled) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = NsColors.Fair, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Android limits apps to 4 Wi-Fi scans every 2 minutes. Showing the latest results.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (list.isEmpty()) {
        item { EmptyState(Icons.Filled.WifiOff, "No networks yet", "Pull the refresh button to scan for nearby Wi-Fi networks.") }
    }
    items(list, key = { it.bssid + it.frequency }) { n ->
        NetworkRow(n, connected = n.bssid.equals(state.connection?.bssid, ignoreCase = true), onClick = { onSelect(n) })
    }
}

private object NetworkFilterState {
    var band by mutableStateOf<WifiBand?>(null)
    var sort by mutableStateOf(WifiSort.SIGNAL)
}

@Composable
private fun NetworkFilters() {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = NetworkFilterState.band == null, onClick = { NetworkFilterState.band = null }, label = { Text("All") })
            listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).forEach { b ->
                FilterChip(selected = NetworkFilterState.band == b, onClick = { NetworkFilterState.band = b }, label = { Text(b.label) })
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(NetworkFilterState.sort.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                WifiSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text("Sort by ${s.label.lowercase()}") }, onClick = { NetworkFilterState.sort = s; menu = false })
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(n: WifiNetwork, connected: Boolean, onClick: () -> Unit) {
    val color = signalColor(n.level)
    AppCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
        containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                SignalBars(Signal.bars(n.level), color, Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text("${n.level}", style = MaterialTheme.typography.labelSmall, color = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        n.displaySsid, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (connected) {
                        Spacer(Modifier.width(6.dp))
                        Pill("Connected", MaterialTheme.colorScheme.primary, Icons.Filled.Star)
                    }
                }
                Text(
                    listOfNotNull("Ch ${n.channel}", n.band.label, "${n.widthMhz} MHz", n.standard?.substringBefore(" (")).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(
                        n.security.label,
                        if (n.security.weak) NsColors.Weak else NsColors.Good,
                        if (n.security.weak) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    )
                    if (n.wps) Pill("WPS", NsColors.Fair, Icons.Filled.Warning)
                    n.vendor?.let { Pill(it.take(22), MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun NetworkDetails(n: WifiNetwork, connected: Boolean) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(n.displaySsid, style = MaterialTheme.typography.headlineSmall)
        if (connected) Text("You're connected to this access point", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        InfoRow("Signal", "${n.level} dBm · ${Signal.quality(n.level)} (${Signal.percent(n.level)}%)")
        InfoRow("BSSID", n.bssid, mono = true)
        InfoRow("Vendor", n.vendor ?: "Unknown (download vendor list in Settings)")
        InfoRow("Channel", "${n.channel} (center ${n.centerChannel})")
        InfoRow("Frequency", "${n.frequency} MHz · ${n.band.label}")
        InfoRow("Width", "${n.widthMhz} MHz")
        InfoRow("Standard", n.standard)
        InfoRow("Security", n.security.label + if (n.wps) " · WPS on" else "")
        InfoRow("Capabilities", n.capabilities, mono = true)
        if (n.security.weak || n.wps) {
            Spacer(Modifier.height(12.dp))
            AppCard(containerColor = NsColors.Weak.copy(alpha = 0.12f)) {
                Text("Security warning", style = MaterialTheme.typography.titleSmall, color = NsColors.Weak)
                Text(
                    buildString {
                        if (n.security.weak) append("${n.security.label} offers little or no protection. Avoid sending sensitive data. ")
                        if (n.wps) append("WPS PIN can be brute-forced; disable it on the router.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.channelsTab(state: WifiViewModel.UiState) {
    item(key = "band") { BandSelector() }
    val band = ChannelBandState.band
    val scores = ChannelUtils.channelScores(state.networks, band)
    val inBand = state.networks.count { it.band == band }
    item(key = "graph") {
        AppCard(Modifier.fillMaxWidth()) {
            Text("Channel occupancy", style = MaterialTheme.typography.titleMedium)
            Text("$inBand networks on ${band.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ChannelGraph(state.networks, band, state.connection?.bssid, Modifier.fillMaxWidth().height(240.dp))
        }
    }
    if (scores.isNotEmpty()) {
        val best = scores.minBy { it.score }
        val current = state.connection?.takeIf { it.band == band }?.channel
        item(key = "best") {
            AppCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) {
                Text("RECOMMENDED CHANNEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Channel ${best.channel}", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        current == null -> "Least congested ${band.label} channel nearby. Set it in your router's wireless settings."
                        current == best.channel -> "Your router is already on the best channel."
                        else -> "Your router is on channel $current. Switching to ${best.channel} may reduce interference."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item(key = "scores") {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Congestion by channel", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val max = (scores.maxOf { it.score }).coerceAtLeast(0.01f)
                scores.forEach { s ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.channel}", Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge)
                        Box(
                            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            val f = (s.score / max).coerceIn(0.02f, 1f)
                            val c = when {
                                s == best -> NsColors.Excellent
                                f > 0.66f -> NsColors.Weak
                                f > 0.33f -> NsColors.Fair
                                else -> NsColors.Good
                            }
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(c))
                        }
                        Text("${s.count} APs", Modifier.width(56.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private object ChannelBandState {
    var band by mutableStateOf(WifiBand.GHZ_2_4)
}

@Composable
private fun BandSelector() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).forEach { b ->
            FilterChip(selected = ChannelBandState.band == b, onClick = { ChannelBandState.band = b }, label = { Text(b.label) })
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.signalTab(state: WifiViewModel.UiState) {
    val conn = state.connection
    if (conn == null || !conn.isWifi || conn.rssi == null) {
        item { EmptyState(Icons.Filled.WifiOff, "Not connected", "Connect to Wi-Fi to use the live signal meter.") }
        return
    }
    val rssi = conn.rssi
    item(key = "gauge") {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(conn.ssid ?: "Current network", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                val color = signalColor(rssi)
                RingGauge(
                    fraction = Signal.percent(rssi) / 100f,
                    modifier = Modifier.size(200.dp),
                    strokeWidth = 16.dp,
                    colors = listOf(color.copy(alpha = 0.7f), color),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$rssi", style = MaterialTheme.typography.displayMedium, color = color)
                        Text("dBm", style = MaterialTheme.typography.labelLarge)
                        Text(Signal.quality(rssi), style = MaterialTheme.typography.titleSmall, color = color)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MeterStat("Link", conn.linkSpeedMbps?.let { "$it Mbps" } ?: "—")
                    MeterStat("Band", conn.band?.label ?: "—")
                    MeterStat("Channel", conn.channel?.toString() ?: "—")
                }
            }
        }
    }
    item(key = "history") {
        val hist = state.rssiHistory
        AppCard(Modifier.fillMaxWidth()) {
            Text("Last 2 minutes", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Sparkline(
                hist.map { it.toFloat() },
                Modifier.fillMaxWidth().height(120.dp),
                color = signalColor(rssi),
                minValue = -95f, maxValue = -25f,
            )
            Spacer(Modifier.height(8.dp))
            if (hist.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MeterStat("Min", "${hist.min()} dBm")
                    MeterStat("Avg", "${hist.average().toInt()} dBm")
                    MeterStat("Max", "${hist.max()} dBm")
                }
            }
        }
    }
    item(key = "tips") {
        Column {
            SectionHeader("Reading the meter")
            AppCard(Modifier.fillMaxWidth()) {
                Tip(NsColors.Excellent, "-30 to -55 dBm", "Excellent — 4K streaming, gaming, video calls")
                Tip(NsColors.Good, "-56 to -67 dBm", "Good — reliable for almost everything")
                Tip(NsColors.Fair, "-68 to -75 dBm", "Fair — browsing OK, calls may stutter")
                Tip(NsColors.Weak, "below -75 dBm", "Weak — move closer or add a mesh node")
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Walk around while watching the graph to find dead zones and the best spot for your router.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeterStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tip(color: androidx.compose.ui.graphics.Color, range: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(range, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
