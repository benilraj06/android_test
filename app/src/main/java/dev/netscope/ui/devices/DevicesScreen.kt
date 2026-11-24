package dev.netscope.ui.devices

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netscope.container
import dev.netscope.data.Exporter
import dev.netscope.data.ScanState
import dev.netscope.net.DeviceType
import dev.netscope.net.NetDevice
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.DeviceAvatar
import dev.netscope.ui.components.EmptyState
import dev.netscope.ui.components.Pill
import dev.netscope.ui.components.RadarSweep
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.timeAgo
import dev.netscope.ui.theme.NsColors
import dev.netscope.ui.theme.latencyColor
import kotlinx.coroutines.launch

class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = app.container.scanController
    val state = controller.state

    init {
        viewModelScope.launch { controller.loadCached() }
    }

    fun start() = controller.start()
    fun stop() = controller.stop()
}

enum class DeviceFilter(val label: String) { ALL("All"), NEW("New"), UNKNOWN("Unidentified"), TRUSTED("Trusted"), UNTRUSTED("Not trusted"), OFFLINE("Offline") }

@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit, vm: DevicesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var chip by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    var typeFilter by rememberSaveable { mutableStateOf<DeviceType?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val filtered = state.devices.filter { d ->
        val q = query.trim().lowercase()
        val matchesQuery = q.isEmpty() || listOfNotNull(d.displayName, d.ip, d.manufacturer, d.mac, d.bestName, d.type.label)
            .any { it.lowercase().contains(q) }
        val matchesFilter = when (chip) {
            DeviceFilter.ALL -> true
            DeviceFilter.NEW -> d.isNew
            DeviceFilter.UNKNOWN -> d.type == DeviceType.UNKNOWN
            DeviceFilter.TRUSTED -> d.isKnown
            DeviceFilter.UNTRUSTED -> !d.isKnown && !d.isSelf
            DeviceFilter.OFFLINE -> !d.online
        }
        matchesQuery && matchesFilter && (typeFilter == null || d.type == typeFilter)
    }
    val online = filtered.filter { it.online }
    val offline = filtered.filter { !it.online }
    val types = state.devices.map { it.type }.distinct().sortedBy { it.ordinal }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Devices") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { exportMenu = true }, enabled = state.devices.isNotEmpty()) {
                        Icon(Icons.Filled.Share, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text("Export as CSV") }, onClick = {
                            exportMenu = false
                            Exporter.share(context, state.devices, "csv", state.ssid)
                        })
                        DropdownMenuItem(text = { Text("Export as JSON") }, onClick = {
                            exportMenu = false
                            Exporter.share(context, state.devices, "json", state.ssid)
                        })
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { ScanHeader(state, onStart = vm::start, onStop = vm::stop) }
            if (searching) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search name, IP, vendor…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                    )
                }
            }
            if (state.devices.isNotEmpty()) {
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DeviceFilter.entries) { f ->
                            FilterChip(selected = chip == f, onClick = { chip = f }, label = { Text(f.label) })
                        }
                    }
                }
                if (types.size > 1) {
                    item(key = "types") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(types) { t ->
                                FilterChip(
                                    selected = typeFilter == t,
                                    onClick = { typeFilter = if (typeFilter == t) null else t },
                                    label = { Text(t.label) },
                                    leadingIcon = { DeviceAvatar(t, 20.dp) },
                                )
                            }
                        }
                    }
                }
            }
            if (state.isolatedHint && !state.running) {
                item(key = "isolated") {
                    AppCard(Modifier.fillMaxWidth(), containerColor = NsColors.Fair.copy(alpha = 0.12f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, null, tint = NsColors.Fair)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "No other devices answered. This may be a guest network with client isolation, " +
                                    "or devices are blocking discovery.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (state.devices.isEmpty() && !state.running) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Filled.Devices,
                        "Discover your network",
                        state.error ?: "Find every phone, laptop, TV and smart gadget connected to your Wi-Fi.",
                    ) {
                        Button(onClick = vm::start) { Text("Scan now") }
                    }
                }
            }
            if (online.isNotEmpty()) {
                item(key = "h_online") { SectionHeader(if (state.fromCache) "From last scan" else "Online · ${online.size}") }
                items(online, key = { "on_" + it.key + it.ip }) { d -> DeviceRow(d) { onOpenDevice(d.ip) } }
            }
            if (offline.isNotEmpty()) {
                item(key = "h_offline") { SectionHeader(if (state.fromCache) "Previously seen · ${offline.size}" else "Offline · ${offline.size}") }
                items(offline, key = { "off_" + it.key + it.ip }) { d -> DeviceRow(d) { onOpenDevice(d.ip) } }
            }
        }
    }
}

@Composable
private fun ScanHeader(state: ScanState, onStart: () -> Unit, onStop: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadarSweep(active = state.running, blips = state.online.map { it.ip }, modifier = Modifier.size(96.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(state.ssid ?: "Local network", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.subnet ?: "Tap scan to discover devices",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.devices.isNotEmpty() && !state.fromCache) Pill("${state.online.size} online", NsColors.Excellent)
                    val newCount = state.devices.count { it.isNew }
                    if (newCount > 0) Pill("$newCount new", NsColors.Fair)
                    if (state.offline.isNotEmpty() && !state.fromCache) Pill("${state.offline.size} offline", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (state.running) {
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (state.lastScan == null) "Scan network" else "Scan again")
                    }
                }
            }
        }
        if (state.running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(state.phase, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.lastScan != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Last scan ${timeAgo(state.lastScan)}" + (state.durationMs?.let { " · took ${it / 1000}s" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeviceRow(d: NetDevice, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(d.type, online = d.online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (d.isKnown) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, "Trusted", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    listOfNotNull(d.ip, d.manufacturer ?: d.type.label.takeIf { d.type != DeviceType.UNKNOWN }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                when {
                    d.isNew -> Pill("NEW", NsColors.Fair)
                    d.isSelf -> Pill("YOU", MaterialTheme.colorScheme.primary)
                    d.isGateway -> Pill("ROUTER", MaterialTheme.colorScheme.secondary)
                }
                if (d.online && d.latencyMs != null && !d.isSelf) {
                    Text("${d.latencyMs} ms", style = MaterialTheme.typography.labelSmall, color = latencyColor(d.latencyMs.toDouble()))
                } else if (!d.online) {
                    Text(timeAgo(d.lastSeen), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
