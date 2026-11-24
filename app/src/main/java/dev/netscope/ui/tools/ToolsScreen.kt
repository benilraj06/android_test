package dev.netscope.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.IconBadge
import dev.netscope.ui.components.SectionHeader
import dev.netscope.ui.theme.NsColors

data class ToolItem(val id: String, val title: String, val subtitle: String, val icon: ImageVector, val tint: Color)

val toolItems = listOf(
    ToolItem("speedtest", "Speed test", "Download, upload & latency", Icons.Filled.Speed, NsColors.Excellent),
    ToolItem("ping", "Ping", "Round-trip time & packet loss", Icons.Filled.NetworkCheck, NsColors.Info),
    ToolItem("traceroute", "Traceroute", "Every hop to a destination", Icons.Filled.Timeline, NsColors.Violet),
    ToolItem("dns", "DNS lookup", "A, AAAA, MX, TXT, NS & more", Icons.Filled.Dns, NsColors.Pink),
    ToolItem("iplookup", "IP lookup", "Location, ISP & ASN", Icons.Filled.Public, Color(0xFF0EA5E9)),
    ToolItem("wol", "Wake-on-LAN", "Power on a computer remotely", Icons.Filled.PowerSettingsNew, NsColors.Fair),
    ToolItem("subnet", "Subnet calculator", "Ranges, masks & host counts", Icons.Filled.Calculate, Color(0xFF14B8A6)),
)

@Composable
fun ToolsScreen(onOpenTool: (String) -> Unit, onOpenSettings: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Tools") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Network utilities") }
            items(toolItems, key = { it.id }) { t ->
                AppCard(onClick = { onOpenTool(t.id) }) {
                    IconBadge(t.icon, t.tint, 44.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
                }
            }
        }
    }
}

@Composable
fun ToolHost(id: String, host: String?, onBack: () -> Unit) {
    when (id) {
        "ping" -> PingScreen(host, onBack)
        "traceroute" -> TracerouteScreen(host, onBack)
        "dns" -> DnsScreen(host, onBack)
        "iplookup" -> IpLookupScreen(host, onBack)
        "wol" -> WolScreen(host, onBack)
        "subnet" -> SubnetScreen(onBack)
        "speedtest" -> SpeedTestScreen(onBack)
        else -> androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
    }
}
