package dev.netscope.ui.tools

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.netscope.container
import dev.netscope.net.DnsAnswer
import dev.netscope.net.DnsTool
import dev.netscope.net.IpDetails
import dev.netscope.net.IpLookup
import dev.netscope.net.IpUtils
import dev.netscope.net.PingTool
import dev.netscope.net.SpeedPhase
import dev.netscope.net.SpeedState
import dev.netscope.net.SpeedTest
import dev.netscope.net.WakeOnLan
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.DetailScaffold
import dev.netscope.ui.components.InfoRow
import dev.netscope.ui.components.Pill
import dev.netscope.ui.components.RingGauge
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.Sparkline
import dev.netscope.ui.theme.NsColors
import dev.netscope.ui.theme.latencyColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- shared pieces ----------

@Composable
private fun ToolList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun HostInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    running: Boolean,
    onStart: () -> Unit,
    onStop: (() -> Unit)? = null,
    startLabel: String = "Start",
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    AppCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); if (!running) onStart() }),
        )
        Spacer(Modifier.height(12.dp))
        if (running && onStop != null) {
            FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
            }
        } else {
            Button(onClick = { keyboard?.hide(); onStart() }, enabled = !running && value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(startLabel)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    AppCard(Modifier.fillMaxWidth(), containerColor = NsColors.Weak.copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, null, tint = NsColors.Weak)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Ping ----------

@Composable
fun PingScreen(initialHost: String?, onBack: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(initialHost ?: "1.1.1.1") }
    var count by rememberSaveable { mutableIntStateOf(10) }
    var running by remember { mutableStateOf(false) }
    var loss by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<String>() }
    val times = remember { mutableStateListOf<Float>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        job?.cancel()
        lines.clear(); times.clear(); loss = null; error = null
        running = true
        job = scope.launch {
            try {
                PingTool.ping(host.trim(), count).collect { line ->
                    lines.add(line)
                    if (lines.size > 200) lines.removeAt(0)
                    PingTool.parseTime(line)?.let { times.add(it.toFloat()) }
                    PingTool.parseLoss(line)?.let { loss = it }
                }
                if (times.isEmpty() && lines.none { it.contains("bytes from") }) {
                    error = lines.lastOrNull { it.contains("unknown host", true) || it.contains("unreachable", true) } ?: "No replies received"
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) error = e.message ?: "Ping failed"
            } finally {
                running = false
            }
        }
    }

    DetailScaffold(title = "Ping", onBack = onBack) { padding ->
        ToolList(padding) {
            item {
                HostInput(host, { host = it }, "Host or IP", running, ::start, onStop = { job?.cancel() })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 10, 50, 0).forEach { n ->
                        FilterChip(selected = count == n, onClick = { count = n }, enabled = !running, label = { Text(if (n == 0) "Continuous" else "$n pings") })
                    }
                }
            }
            error?.let { item { ErrorCard(it) } }
            if (times.isNotEmpty()) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            BigStat("min ms", "%.1f".format(times.min()), modifier = Modifier.weight(1f))
                            BigStat("avg ms", "%.1f".format(times.average()), latencyColor(times.average()), Modifier.weight(1f))
                            BigStat("max ms", "%.1f".format(times.max()), modifier = Modifier.weight(1f))
                            BigStat("loss", loss?.let { "%.0f%%".format(it) } ?: "—", if ((loss ?: 0.0) > 0) NsColors.Weak else NsColors.Excellent, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Sparkline(times.toList(), Modifier.fillMaxWidth().height(100.dp), color = latencyColor(times.average()), minValue = 0f)
                        Spacer(Modifier.height(4.dp))
                        Text("${times.size} replies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (lines.isNotEmpty()) {
                item { SectionHeader("Output") }
                item {
                    AppCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        lines.takeLast(40).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Traceroute ----------

@Composable
fun TracerouteScreen(initialHost: String?, onBack: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(initialHost ?: "google.com") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hops = remember { mutableStateListOf<PingTool.Hop>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        job?.cancel()
        hops.clear(); error = null; running = true
        job = scope.launch {
            try {
                PingTool.traceroute(host.trim()).collect { hops.add(it) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) error = e.message ?: "Traceroute failed"
            } finally {
                running = false
            }
        }
    }

    DetailScaffold(title = "Traceroute", onBack = onBack) { padding ->
        ToolList(padding) {
            item { HostInput(host, { host = it }, "Destination", running, ::start, onStop = { job?.cancel() }, startLabel = "Trace route") }
            error?.let { item { ErrorCard(it) } }
            if (hops.isNotEmpty()) {
                item {
                    val reached = hops.lastOrNull()?.reached == true
                    Text(
                        if (running) "Tracing… ${hops.size} hops" else if (reached) "Reached destination in ${hops.size} hops" else "${hops.size} hops",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(hops, key = { _, h -> h.ttl }) { i, h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(
                                if (h.reached) NsColors.Excellent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${h.ttl}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        if (i < hops.lastIndex) Box(Modifier.width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Spacer(Modifier.width(12.dp))
                    AppCard(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.ip ?: "* * *", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                                Text(
                                    h.hostname ?: if (h.ip == null) "No response" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            h.timeMs?.let { Text("%.0f ms".format(it), style = MaterialTheme.typography.labelLarge, color = latencyColor(it)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------- DNS ----------

@Composable
fun DnsScreen(initialHost: String?, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialHost ?: "example.com") }
    var type by rememberSaveable { mutableStateOf("A") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answers by remember { mutableStateOf<List<DnsAnswer>?>(null) }
    var dohMs by remember { mutableStateOf<Long?>(null) }
    var system by remember { mutableStateOf<Pair<List<String>, Long>?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        running = true; error = null; answers = null; system = null
        scope.launch {
            try {
                val (list, ms) = DnsTool.query(name, type)
                answers = list; dohMs = ms
                if (type == "A" || type == "AAAA") system = runCatching { DnsTool.systemResolve(name) }.getOrNull()
            } catch (e: Exception) {
                error = e.message ?: "Lookup failed"
            } finally {
                running = false
            }
        }
    }

    DetailScaffold(title = "DNS lookup", onBack = onBack) { padding ->
        ToolList(padding) {
            item { HostInput(name, { name = it }, if (type == "PTR") "IP address" else "Domain", running, ::start, startLabel = "Look up") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DnsTool.TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
            }
            error?.let { item { ErrorCard(it) } }
            answers?.let { list ->
                item {
                    Text(
                        "${list.size} record${if (list.size == 1) "" else "s"} · ${dohMs} ms via Cloudflare DoH",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (list.isEmpty()) item { AppCard(Modifier.fillMaxWidth()) { Text("No $type records found.") } }
                items(list) { a ->
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(a.type, NsColors.Pink)
                            Spacer(Modifier.width(8.dp))
                            Text(a.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("TTL ${a.ttl}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(a.data, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            system?.let { (list, ms) ->
                item { SectionHeader("System resolver (${ms} ms)") }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        list.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

// ---------- IP lookup ----------

@Composable
fun IpLookupScreen(initialHost: String?, onBack: () -> Unit) {
    var ip by rememberSaveable { mutableStateOf(initialHost ?: "") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<IpDetails?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        running = true; error = null
        scope.launch {
            try {
                result = IpLookup.lookup(ip.ifBlank { null })
            } catch (e: Exception) {
                error = e.message ?: "Lookup failed"
            } finally {
                running = false
            }
        }
    }
    LaunchedEffect(Unit) { if (result == null) start() }

    DetailScaffold(title = "IP lookup", onBack = onBack) { padding ->
        ToolList(padding) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP address (blank = your public IP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { start() }),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = ::start, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                        if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Look up")
                    }
                }
            }
            error?.let { item { ErrorCard(it) } }
            result?.let { r ->
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.flag.ifEmpty { "🌐" }, style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(r.ip, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                                Text(r.location.ifEmpty { "Unknown location" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        InfoRow("ISP", r.isp)
                        InfoRow("Organization", r.org)
                        InfoRow("ASN", r.asn)
                        InfoRow("Domain", r.domain)
                        InfoRow("Type", r.type)
                        InfoRow("Continent", r.continent)
                        InfoRow("Timezone", r.timezone)
                        InfoRow("Coordinates", if (r.latitude != null && r.longitude != null) "%.4f, %.4f".format(r.latitude, r.longitude) else null)
                    }
                }
                item {
                    Text(
                        "Location is approximate and based on public IP registration data (ipwho.is).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Wake-on-LAN ----------

@Composable
fun WolScreen(initialMac: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var mac by rememberSaveable { mutableStateOf(initialMac ?: "") }
    var broadcast by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9") }
    var sending by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (broadcast.isEmpty()) {
            val conn = withContext(Dispatchers.IO) { context.container.networkInfo.current() }
            broadcast = conn.ipv4?.let { IpUtils.subnetInfo(it, conn.prefixLength ?: 24).broadcast } ?: "255.255.255.255"
        }
    }
    val validMac = WakeOnLan.parseMac(mac) != null

    DetailScaffold(title = "Wake-on-LAN", onBack = onBack, snackbarHostState = snackbar) { padding ->
        ToolList(padding) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = mac, onValueChange = { mac = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("MAC address") }, placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        isError = mac.isNotEmpty() && !validMac,
                        supportingText = { if (mac.isNotEmpty() && !validMac) Text("Enter 12 hex digits") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = broadcast, onValueChange = { broadcast = it }, singleLine = true, label = { Text("Broadcast") }, modifier = Modifier.weight(2f))
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("Port") },
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            sending = true
                            scope.launch {
                                val r = runCatching { WakeOnLan.send(mac, broadcast.trim(), port.toIntOrNull() ?: 9) }
                                sending = false
                                snackbar.showSnackbar(if (r.isSuccess) "Magic packet sent" else "Failed: ${r.exceptionOrNull()?.message}")
                            }
                        },
                        enabled = validMac && !sending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send magic packet") }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("How it works", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The target computer must have Wake-on-LAN enabled in its BIOS/UEFI and network adapter settings, " +
                            "and usually needs a wired Ethernet connection. Android 10+ hides other devices' MAC addresses, " +
                            "so copy it from the computer (ipconfig /all, ip link) or your router's client list.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Subnet calculator ----------

@Composable
fun SubnetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ip by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableIntStateOf(24) }
    LaunchedEffect(Unit) {
        if (ip.isEmpty()) {
            val conn = withContext(Dispatchers.IO) { context.container.networkInfo.current() }
            ip = conn.ipv4 ?: "192.168.1.10"
            prefix = conn.prefixLength ?: 24
        }
    }
    val valid = IpUtils.isValidIpv4(ip)
    val info = if (valid) IpUtils.subnetInfo(ip.trim(), prefix) else null

    DetailScaffold(title = "Subnet calculator", onBack = onBack) { padding ->
        ToolList(padding) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP address") }, isError = ip.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prefix", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text("/$prefix", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = prefix.toFloat(), onValueChange = { prefix = it.toInt() }, valueRange = 1f..32f, steps = 30)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(8, 16, 22, 24, 28, 30).forEach { p -> FilterChip(selected = prefix == p, onClick = { prefix = p }, label = { Text("/$p") }) }
                    }
                }
            }
            info?.let { s ->
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(s.cidr, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            "%,d usable hosts · Class %s · %s".format(s.hostCount, s.ipClass, if (s.isPrivate) "Private" else "Public"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        InfoRow("Network", s.network, mono = true)
                        InfoRow("Broadcast", s.broadcast, mono = true)
                        InfoRow("Subnet mask", s.mask, mono = true)
                        InfoRow("Wildcard", s.wildcard, mono = true)
                        InfoRow("First host", s.firstHost, mono = true)
                        InfoRow("Last host", s.lastHost, mono = true)
                        InfoRow("Binary mask", IpUtils.maskFromPrefix(prefix).toString(2).padStart(32, '0').chunked(8).joinToString("."), mono = true)
                    }
                }
            }
        }
    }
}

// ---------- Speed test ----------

@Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf(SpeedState()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val running = state.phase in listOf(SpeedPhase.PING, SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD)

    fun start() {
        job?.cancel()
        state = SpeedState(phase = SpeedPhase.PING)
        job = scope.launch {
            try {
                SpeedTest.run().collect { state = it }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) state = state.copy(phase = SpeedPhase.ERROR, error = e.message ?: "Test failed")
            }
        }
    }

    val live = when (state.phase) {
        SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD -> state.liveMbps
        SpeedPhase.DONE -> state.downloadMbps ?: 0.0
        else -> 0.0
    }
    val scale = when {
        live > 500 -> 1000.0
        live > 100 -> 500.0
        live > 50 -> 100.0
        else -> 50.0
    }
    val phaseColor = if (state.phase == SpeedPhase.UPLOAD) NsColors.Violet else NsColors.Excellent

    DetailScaffold(title = "Speed test", onBack = onBack) { padding ->
        ToolList(padding) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RingGauge(
                            fraction = (live / scale).toFloat(),
                            modifier = Modifier.size(220.dp),
                            strokeWidth = 18.dp,
                            colors = listOf(phaseColor.copy(alpha = 0.6f), phaseColor),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.phase.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (live > 0) "%.1f".format(live) else "—", style = MaterialTheme.typography.displayMedium)
                                Text("Mbps", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (running) {
                            FilledTonalButton(onClick = { job?.cancel(); state = state.copy(phase = SpeedPhase.IDLE, liveMbps = 0.0) }) {
                                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                            }
                        } else {
                            Button(onClick = ::start) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                                Text(if (state.phase == SpeedPhase.DONE) "Run again" else "Start test")
                            }
                        }
                    }
                }
            }
            state.error?.let { item { ErrorCard(it) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedTile("Download", state.downloadMbps?.let { "%.1f".format(it) }, "Mbps", NsColors.Excellent, Modifier.weight(1f))
                    SpeedTile("Upload", state.uploadMbps?.let { "%.1f".format(it) }, "Mbps", NsColors.Violet, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedTile("Latency", state.pingMs?.let { "%.0f".format(it) }, "ms", latencyColor(state.pingMs), Modifier.weight(1f))
                    SpeedTile("Jitter", state.jitterMs?.let { "%.1f".format(it) }, "ms", NsColors.Info, Modifier.weight(1f))
                }
            }
            item {
                Text(
                    "Measured against Cloudflare's global network (speed.cloudflare.com). Uses about 125 MB of data.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedTile(label: String, value: String?, unit: String, color: Color, modifier: Modifier) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: "—", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
