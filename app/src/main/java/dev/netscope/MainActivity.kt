package dev.netscope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netscope.data.AppSettings
import dev.netscope.data.ThemeMode
import dev.netscope.ui.NetScopeApp
import dev.netscope.ui.theme.NetScopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openDevices = intent?.getStringExtra(EXTRA_OPEN) == "devices"
        setContent {
            val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            val dark = when (s?.themeMode ?: ThemeMode.SYSTEM) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            NetScopeTheme(darkTheme = dark, dynamicColor = s?.dynamicColor ?: false) {
                if (s != null) NetScopeApp(settings = s, openDevices = openDevices)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
