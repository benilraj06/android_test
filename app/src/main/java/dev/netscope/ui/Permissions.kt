package dev.netscope.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.netscope.ui.components.AppCard
import dev.netscope.ui.components.IconBadge

object Perms {
    fun wifiPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    fun hasWifi(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}

@Stable
class WifiPermissionState(granted: Boolean, locationOn: Boolean, private val requestFn: () -> Unit) {
    var granted by mutableStateOf(granted)
    var locationOn by mutableStateOf(locationOn)
    val ready: Boolean get() = granted && locationOn
    fun request() = requestFn()
}

@Composable
fun rememberWifiPermissionState(onGranted: () -> Unit = {}): WifiPermissionState {
    val context = LocalContext.current
    var stateRef: WifiPermissionState? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        stateRef?.granted = Perms.hasWifi(context)
        if (Perms.hasWifi(context)) onGranted()
    }
    val state = remember {
        WifiPermissionState(Perms.hasWifi(context), Perms.locationEnabled(context)) {
            launcher.launch(Perms.wifiPermissions())
        }
    }
    stateRef = state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val was = state.ready
        state.granted = Perms.hasWifi(context)
        state.locationOn = Perms.locationEnabled(context)
        if (!was && state.ready) onGranted()
    }
    return state
}

@Composable
fun PermissionCard(state: WifiPermissionState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AppCard(modifier) {
        IconBadge(Icons.Filled.LocationOn, MaterialTheme.colorScheme.tertiary, 44.dp)
        Spacer(Modifier.height(12.dp))
        if (!state.granted) {
            Text("Location access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android only shares Wi-Fi names and scan results with apps that have location permission. " +
                    "NetScope never stores or sends your location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { state.request() }) { Text("Grant access") }
        } else {
            Text("Turn on Location", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Wi-Fi scanning requires the system Location setting to be on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open settings") }
        }
    }
}
