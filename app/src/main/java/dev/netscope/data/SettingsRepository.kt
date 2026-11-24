package dev.netscope.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val monitorEnabled: Boolean = false,
    val monitorIntervalMin: Int = 60,
    val portTimeoutMs: Int = 400,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val disclaimer = booleanPreferencesKey("disclaimer")
        val monitor = booleanPreferencesKey("monitor")
        val interval = intPreferencesKey("monitor_interval")
        val timeout = intPreferencesKey("port_timeout")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: false,
            disclaimerAccepted = p[Keys.disclaimer] ?: false,
            monitorEnabled = p[Keys.monitor] ?: false,
            monitorIntervalMin = p[Keys.interval] ?: 60,
            portTimeoutMs = p[Keys.timeout] ?: 400,
        )
    }

    suspend fun setTheme(mode: ThemeMode) { context.dataStore.edit { it[Keys.theme] = mode.name } }
    suspend fun setDynamicColor(v: Boolean) { context.dataStore.edit { it[Keys.dynamic] = v } }
    suspend fun acceptDisclaimer() { context.dataStore.edit { it[Keys.disclaimer] = true } }
    suspend fun setMonitor(v: Boolean) { context.dataStore.edit { it[Keys.monitor] = v } }
    suspend fun setInterval(min: Int) { context.dataStore.edit { it[Keys.interval] = min } }
    suspend fun setPortTimeout(ms: Int) { context.dataStore.edit { it[Keys.timeout] = ms } }
}
