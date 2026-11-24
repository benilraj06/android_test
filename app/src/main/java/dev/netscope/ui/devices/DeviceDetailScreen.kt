package dev.netscope.ui.devices

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netscope.container
import dev.netscope.net.NetDevice
import dev.netscope.net.OpenPort
import dev.netscope.net.PortProfile
import dev.netscope.net.PortScanEvent
import dev.netscope.net.Ports
import dev.netscope.net.Risk
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.DetailScaffold
import dev.netscope.ui.components.DeviceAvatar
import dev.netscope.ui.components.EmptyState
import dev.netscope.ui.components.InfoRow
import dev.netscope.ui.components.Pill
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.timeAgo
import dev.netscope.ui.theme.NsColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DeviceDetailViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val c = app.container
    val ip: String = Uri.decode(handle.get<String>("ip").orEmpty())

    val device = c.scanController.state
        .map { s -> s.devices.firstOrNull { it.ip == ip } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.scanController.state.value.devices.firstOrNull { it.ip == ip })

    data class PortUi(
        val profile: PortProfile = PortProfile.QUICK,
        val from: String = "1",
        val to: String = "1024",
        val running: Boolean = false,
        val scanned: Int = 0,
        val total: Int = 0,
        val open: List<OpenPort> = emptyList(),
        val finishedAt: Long? = null,
        val durationMs: Long? = null,
    )

    private val _ports = MutableStateFlow(PortUi())
    val ports = _ports.asStateFlow()
    private var job: Job? = null

    fun setProfile(p: PortProfile) = _ports.update { it.copy(profile = p) }
    fun setRange(from: String, to: String) = _ports.update { it.copy(from = from.filter(Char::isDigit).take(5), to = to.filter(Char::isDigit).take(5)) }

    fun startPortScan() {
        job?.cancel()
        val p = _ports.value
        val list = p.profile.ports(p.from.toIntOrNull() ?: 1, p.to.toIntOrNull() ?: 1024)
        job = viewModelScope.launch {
            val timeout = c.settings.settings.first().portTimeoutMs
            val started = System.currentTimeMillis()
            _ports.update { it.copy(running = true, scanned = 0, total = list.size, open = emptyList(), finishedAt = null) }
            try {
                c.portScanner.scan(ip, list, timeout).collect { ev ->
                    when (ev) {
                        is PortScanEvent.Found -> _ports.update { s -> s.copy(open = (s.open + ev.port).sortedBy { it.port }) }
                        is PortScanEvent.Progress -> _ports.update { it.copy(scanned = ev.scanned) }
                    }
                }
                val now = System.currentTimeMillis()
                _ports.update { it.copy(running = false, finishedAt = now, durationMs = now - started) }
                val open = _ports.value.open.map { it.port }
                val d = device.value
                val netId = c.scanController.state.value.networkId
                c.scanController.patch(ip) { it.copy(openPorts = open, lastPortScan = now) }
                if (d != null && netId != null) c.deviceRepository.setPorts(netId, d.key, open, now)
            } finally {
                _ports.update { it.copy(running = false) }
            }
        }
    }

    fun stopPortScan() {
        job?.cancel()
    }

    private fun withKey(block: suspend (String, NetDevice) -> Unit) {
        val d = device.value ?: return
        val netId = c.scanController.state.value.networkId ?: return
        viewModelScope.launch { runCatching { block(netId, d) } }
    }

    fun rename(name: String) {
        val v = name.trim().ifEmpty { null }
        c.scanController.patch(ip) { it.copy(customName = v) }
        withKey { n, d -> c.deviceRepository.rename(n, d.key, v) }
    }

    fun toggleTrusted() {
        val known = !(device.value?.isKnown ?: false)
        c.scanController.patch(ip) { it.copy(isKnown = known, isNew = false) }
        withKey { n, d -> c.deviceRepository.setKnown(n, d.key, known) }
    }

    fun saveNotes(text: String) {
        val v = text.trim().ifEmpty { null }
        c.scanController.patch(ip) { it.copy(notes = v) }
        withKey { n, d -> c.deviceRepository.setNotes(n, d.key, v) }
    }

    fun forget(onDone: () -> Unit) {
        withKey { n, d -> c.deviceRepository.delete(n, d.key) }
        c.scanController.remove(ip)
        onDone()
    }
}

@Composable
fun DeviceDetailScreen(onBack: () -> Unit, onOpenTool: (String, String?) -> Unit, vm: DeviceDetailViewModel = viewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    val ports by vm.ports.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val d = device

    DetailScaffold(
        title = d?.displayName ?: vm.ip,
        onBack = onBack,
        actions = {
            if (d != null) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
        },
    ) { padding ->
        if (d == null) {
            EmptyState(Icons.Filled.NetworkCheck, "Device not found", "Run a network scan and try again.", Modifier.padding(padding))
            return@DetailScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") { DeviceHeader(d, onToggleTrusted = vm::toggleTrusted) }
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onOpenTool("ping", d.ip) }, label = { Text("Ping") }, leadingIcon = { Icon(Icons.Filled.NetworkCheck, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onOpenTool("traceroute", d.ip) }, label = { Text("Traceroute") }, leadingIcon = { Icon(Icons.Filled.Timeline, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onOpenTool("wol", d.mac) }, label = { Text("Wake") }, leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(18.dp)) })
                    val web = (d.openPorts + d.probePorts).firstOrNull { it in listOf(80, 443, 8080, 8443, 8123, 5000, 5001) }
                    if (web != null || d.isGateway) {
                        AssistChip(
                            onClick = {
                                val p = web ?: 80
                                val scheme = if (p in Ports.tlsPorts) "https" else "http"
                                runCatching { uri.openUri("$scheme://${d.ip}:$p") }
                            },
                            label = { Text("Open web UI") },
                            leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            item(key = "info") { DeviceInfo(d) }
            item(key = "ports") {
                PortScanCard(
                    ports = ports,
                    lastScan = d.lastPortScan,
                    onProfile = vm::setProfile,
                    onRange = vm::setRange,
                    onStart = vm::startPortScan,
                    onStop = vm::stopPortScan,
                )
            }
            if (ports.open.isNotEmpty()) {
                item(key = "risk") { RiskSummary(ports.open) }
                items(ports.open, key = { "p" + it.port }) { PortRow(it) }
            } else if (!ports.running && ports.finishedAt != null) {
                item(key = "noports") {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("No open ports found", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "All ${ports.total} scanned ports are closed or filtered. This is a good sign for security.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (!ports.running && d.openPorts.isNotEmpty()) {
                item(key = "prev") {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Open ports from last scan", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.openPorts.forEach { p -> Pill("$p ${Ports.name(p)}", riskColor(Ports.risk(p)?.first)) }
                        }
                    }
                }
            }
            item(key = "notes") { NotesCard(d.notes, vm::saveNotes) }
            if (!d.online && !d.isSelf && !d.isGateway) {
                item(key = "forget") {
                    OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text("Forget this device")
                    }
                }
            }
        }
    }

    if (renaming && d != null) {
        var name by remember { mutableStateOf(d.customName ?: d.bestName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text(d.ip) })
            },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget device?") },
            text = { Text("Its name, notes and history will be removed. It will show up as new if it rejoins.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.forget(onBack) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceHeader(d: NetDevice, onToggleTrusted: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(d.type, size = 64.dp, online = d.online)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, style = MaterialTheme.typography.titleLarge)
                Text(d.type.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (d.online) "Online" else "Offline", if (d.online) NsColors.Excellent else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.isNew) Pill("New", NsColors.Fair)
                    if (d.isSelf) Pill("This device", MaterialTheme.colorScheme.primary)
                    if (d.isGateway) Pill("Gateway", MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (!d.isSelf) {
            Spacer(Modifier.height(12.dp))
            if (d.isKnown) {
                FilledTonalButton(onClick = onToggleTrusted, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Verified, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Trusted device")
                }
            } else {
                OutlinedButton(onClick = onToggleTrusted, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.VerifiedUser, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Mark as trusted")
                }
            }
        }
    }
}

@Composable
private fun DeviceInfo(d: NetDevice) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column {
        SectionHeader("Details")
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            InfoRow("IP address", d.ip, mono = true)
            InfoRow("MAC address", d.mac ?: if (d.isSelf) "Hidden by Android" else "Hidden by Android 10+", mono = d.mac != null)
            InfoRow("Manufacturer", d.manufacturer)
            InfoRow("Hostname", d.hostname)
            InfoRow("Bonjour name", d.mdnsName)
            InfoRow("NetBIOS name", d.netbiosName)
            d.model?.let { InfoRow("Model", it) }
            d.upnp?.let { u ->
                InfoRow("UPnP device", listOfNotNull(u.manufacturer, u.modelName).joinToString(" ").ifEmpty { null })
                InfoRow("UPnP type", u.deviceType)
                InfoRow("Server", u.server)
            }
            InfoRow("Response time", d.latencyMs?.let { "$it ms" })
            if (d.firstSeen > 0) InfoRow("First seen", fmt.format(Date(d.firstSeen)))
            if (d.lastSeen > 0) InfoRow("Last seen", "${fmt.format(Date(d.lastSeen))} (${timeAgo(d.lastSeen)})")
        }
        if (d.services.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("Advertised services")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.services.sorted().forEach { s -> Pill(s.removePrefix("_").substringBefore("._"), MaterialTheme.colorScheme.secondary) }
            }
        }
    }
}

@Composable
private fun PortScanCard(
    ports: DeviceDetailViewModel.PortUi,
    lastScan: Long?,
    onProfile: (PortProfile) -> Unit,
    onRange: (String, String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column {
        SectionHeader("Port scanner")
        AppCard(Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PortProfile.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = ports.profile == p,
                        onClick = { onProfile(p) },
                        shape = SegmentedButtonDefaults.itemShape(i, PortProfile.entries.size),
                        enabled = !ports.running,
                    ) { Text(p.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (ports.profile == PortProfile.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ports.from, onValueChange = { onRange(it, ports.to) }, label = { Text("From") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = ports.to, onValueChange = { onRange(ports.from, it) }, label = { Text("To") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when (ports.profile) {
                    PortProfile.QUICK -> "${Ports.quick.size} most common service ports. Takes a few seconds."
                    PortProfile.COMMON -> "All well-known ports 1–1024 plus common services."
                    PortProfile.FULL -> "Every TCP port. Can take a few minutes."
                    PortProfile.CUSTOM -> "Scan a specific port range."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (ports.running) {
                LinearProgressIndicator(
                    progress = { if (ports.total == 0) 0f else ports.scanned.toFloat() / ports.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ports.scanned} / ${ports.total} · ${ports.open.size} open",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onStop) { Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            ports.finishedAt != null -> "Found ${ports.open.size} open of ${ports.total} in ${(ports.durationMs ?: 0) / 1000.0}s"
                            lastScan != null -> "Last scanned ${timeAgo(lastScan)}"
                            else -> "Not scanned yet"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onStart) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan ports") }
                }
            }
        }
    }
}

fun riskColor(r: Risk?): androidx.compose.ui.graphics.Color = when (r) {
    Risk.CRITICAL -> NsColors.Weak
    Risk.HIGH -> androidx.compose.ui.graphics.Color(0xFFF97316)
    Risk.MEDIUM -> NsColors.Fair
    Risk.LOW -> NsColors.Info
    Risk.INFO, null -> NsColors.Good
}

@Composable
private fun RiskSummary(open: List<OpenPort>) {
    val counts = open.groupingBy { it.risk }.eachCount()
    val worst = open.mapNotNull { it.risk }.maxByOrNull { it.ordinal }
    AppCard(Modifier.fillMaxWidth(), containerColor = riskColor(worst).copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, null, tint = riskColor(worst))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (worst) {
                        Risk.CRITICAL, Risk.HIGH -> "Security issues found"
                        Risk.MEDIUM -> "Some services need attention"
                        else -> "No serious issues"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("${open.size} open ports", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Risk.CRITICAL, Risk.HIGH, Risk.MEDIUM, Risk.LOW).forEach { r ->
                val n = counts[r] ?: 0
                if (n > 0) Pill("$n ${r.label.lowercase()}", riskColor(r))
            }
        }
    }
}

@Composable
private fun PortRow(p: OpenPort) {
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).background(riskColor(p.risk).copy(alpha = 0.15f)).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${p.port}", style = MaterialTheme.typography.titleSmall, color = riskColor(p.risk), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.service, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    p.risk?.let { Pill(it.label, riskColor(it)) }
                }
                p.banner?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                p.riskNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text("${p.latencyMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NotesCard(notes: String?, onSave: (String) -> Unit) {
    var text by remember(notes) { mutableStateOf(notes.orEmpty()) }
    Column {
        SectionHeader("Notes")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Living room TV, bought 2023") },
            minLines = 2,
        )
        if (text != notes.orEmpty()) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(text) }) { Text("Save notes") }
        }
    }
}
