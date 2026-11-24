package dev.netscope.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.netscope.container
import dev.netscope.data.AppSettings
import dev.netscope.data.ThemeMode
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.DetailScaffold
import dev.netscope.ui.components.IconBadge
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.components.timeAgo
import dev.netscope.work.MonitorWorker
import dev.netscope.work.Notifications
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var ouiBusy by remember { mutableStateOf(false) }
    var ouiMessage by remember { mutableStateOf<String?>(null) }
    var ouiCount by remember { mutableIntStateOf(-1) }
    var confirmClear by remember { mutableStateOf(false) }
    var timeout by remember(settings.portTimeoutMs) { mutableFloatStateOf(settings.portTimeoutMs.toFloat()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        ouiCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.oui.entryCount }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            c.settings.setMonitor(true)
            MonitorWorker.schedule(context, settings.monitorIntervalMin)
        }
        if (!granted) ouiMessage = "Notifications are off — alerts won't be shown."
    }

    DetailScaffold(title = "Settings", onBack = onBack) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Appearance") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SettingTitle(Icons.Filled.DarkMode, "Theme", "Choose light, dark, or follow the system")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(selected = settings.themeMode == m, onClick = { scope.launch { c.settings.setTheme(m) } }, label = { Text(m.label) })
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        Spacer(Modifier.height(12.dp))
                        SwitchRow(Icons.Filled.Palette, "Dynamic color", "Use colors from your wallpaper", settings.dynamicColor) {
                            scope.launch { c.settings.setDynamicColor(it) }
                        }
                    }
                }
            }

            item { SectionHeader("Monitoring") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SwitchRow(Icons.Filled.Notifications, "New device alerts", "Scan in the background and notify when an unknown device joins", settings.monitorEnabled) { on ->
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                scope.launch { c.settings.setMonitor(true); MonitorWorker.schedule(context, settings.monitorIntervalMin) }
                            }
                        } else {
                            scope.launch { c.settings.setMonitor(false); MonitorWorker.cancel(context) }
                        }
                    }
                    if (settings.monitorEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("Check every", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60, 180, 360).forEach { m ->
                                FilterChip(
                                    selected = settings.monitorIntervalMin == m,
                                    onClick = { scope.launch { c.settings.setInterval(m); MonitorWorker.schedule(context, m) } },
                                    label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                                )
                            }
                        }
                        Text(
                            "Runs only on Wi-Fi. Android may delay checks to save battery. The first scan of a network sets the baseline.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionHeader("Scanning") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SettingTitle(Icons.Filled.Timer, "Port timeout", "Higher is more accurate on slow devices, lower is faster")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = timeout, onValueChange = { timeout = it }, valueRange = 100f..2000f, steps = 18,
                            onValueChangeFinished = { scope.launch { c.settings.setPortTimeout(timeout.toInt()) } },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("${timeout.toInt()} ms", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SettingTitle(
                        Icons.Filled.Download, "Vendor database",
                        if (ouiCount > 0) "$ouiCount manufacturers · updated ${timeAgo(c.oui.lastUpdated)}"
                        else "Identify router and device manufacturers from MAC addresses (~2 MB download)",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            enabled = !ouiBusy,
                            onClick = {
                                ouiBusy = true; ouiMessage = null
                                scope.launch {
                                    val r = runCatching { c.oui.download() }
                                    ouiBusy = false
                                    r.onSuccess { ouiCount = it; ouiMessage = "Downloaded $it vendors" }
                                    r.onFailure { ouiMessage = "Download failed: ${it.message}" }
                                }
                            },
                        ) { Text(if (ouiCount > 0) "Update" else "Download") }
                        if (ouiBusy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    ouiMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionHeader("Data") }
            item {
                AppCard(Modifier.fillMaxWidth(), onClick = { confirmClear = true }) {
                    SettingTitle(Icons.Filled.Delete, "Clear device history", "Remove saved devices, names, notes and scan history")
                }
            }

            item { SectionHeader("About") }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SettingTitle(Icons.Filled.Info, "NetScope 1.0", "Wi-Fi analyzer, device discovery and network toolkit")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only scan networks you own or are authorized to test. All scanning happens on your device; " +
                            "internet services are used only for public IP lookup (ipwho.is), DNS lookups (Cloudflare), " +
                            "the speed test (Cloudflare) and the vendor list download (Wireshark/IEEE).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All saved devices, custom names, notes and scan history will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { c.deviceRepository.clearAll() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingTitle(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { SettingTitle(icon, title, subtitle) }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
