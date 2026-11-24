package dev.netscope.ui

import android.net.Uri
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.netscope.container
import dev.netscope.data.AppSettings
import dev.netscope.ui.devices.DeviceDetailScreen
import dev.netscope.ui.devices.DevicesScreen
import dev.netscope.ui.overview.OverviewScreen
import dev.netscope.ui.settings.SettingsScreen
import dev.netscope.ui.tools.ToolHost
import dev.netscope.ui.tools.ToolsScreen
import dev.netscope.ui.wifi.WifiScreen
import kotlinx.coroutines.launch

object Routes {
    const val OVERVIEW = "overview"
    const val WIFI = "wifi"
    const val DEVICES = "devices"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val DEVICE = "device/{ip}"
    const val TOOL = "tool/{id}?host={host}"
    fun device(ip: String) = "device/${Uri.encode(ip)}"
    fun tool(id: String, host: String? = null) = "tool/$id" + (host?.let { "?host=${Uri.encode(it)}" } ?: "")
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.OVERVIEW, "Overview", Icons.Filled.Dashboard),
    Tab(Routes.WIFI, "Wi-Fi", Icons.Filled.Wifi),
    Tab(Routes.DEVICES, "Devices", Icons.Filled.Devices),
    Tab(Routes.TOOLS, "Tools", Icons.Filled.Build),
)

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun NetScopeApp(settings: AppSettings, openDevices: Boolean) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    LaunchedEffect(openDevices) {
        if (openDevices) nav.navigateTab(Routes.DEVICES)
    }

    if (!settings.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.Shield, null) },
            title = { Text("Scan responsibly") },
            text = {
                Text(
                    "NetScope can discover devices and scan ports. Only scan networks you own or have explicit " +
                        "permission to test — scanning other people's networks may be against the law or their terms of use.",
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { context.container.settings.acceptDisclaimer() } }) { Text("I understand") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.OVERVIEW,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
        ) {
            composable(Routes.OVERVIEW) {
                OverviewScreen(
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onNavigateTab = { nav.navigateTab(it) },
                    onOpenTool = { id, host -> nav.navigate(Routes.tool(id, host)) },
                    onOpenDevice = { nav.navigate(Routes.device(it)) },
                )
            }
            composable(Routes.WIFI) { WifiScreen() }
            composable(Routes.DEVICES) {
                DevicesScreen(onOpenDevice = { nav.navigate(Routes.device(it)) })
            }
            composable(Routes.TOOLS) {
                ToolsScreen(onOpenTool = { nav.navigate(Routes.tool(it)) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(settings = settings, onBack = { nav.popBackStack() }) }
            composable(Routes.DEVICE, arguments = listOf(navArgument("ip") { type = NavType.StringType })) {
                DeviceDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenTool = { id, host -> nav.navigate(Routes.tool(id, host)) },
                )
            }
            composable(
                Routes.TOOL,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                ToolHost(
                    id = entry.arguments?.getString("id").orEmpty(),
                    host = entry.arguments?.getString("host"),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
