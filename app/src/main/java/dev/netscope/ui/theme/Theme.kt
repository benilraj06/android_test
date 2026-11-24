package dev.netscope.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8C4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0E4F4A),
    onPrimaryContainer = Color(0xFFA6F5E8),
    secondary = Color(0xFF8AA4FF),
    onSecondary = Color(0xFF0B1F66),
    secondaryContainer = Color(0xFF26387A),
    onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0A0F1A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0A0F1A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2536),
    onSurfaceVariant = Color(0xFF9AA7BD),
    surfaceContainerLowest = Color(0xFF070B13),
    surfaceContainerLow = Color(0xFF111827),
    surfaceContainer = Color(0xFF141C2C),
    surfaceContainerHigh = Color(0xFF1A2335),
    surfaceContainerHighest = Color(0xFF222C40),
    outline = Color(0xFF3A4760),
    outlineVariant = Color(0xFF243046),
    error = Color(0xFFFF8A8A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F0E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF3F5BD8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1FF),
    onSecondaryContainer = Color(0xFF001257),
    tertiary = Color(0xFFB25E00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF526077),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE2E7EF),
    outline = Color(0xFFB5BFCE),
    outlineVariant = Color(0xFFDCE2EB),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
    )
}

@Composable
fun NetScopeTheme(darkTheme: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

object NsColors {
    val Excellent = Color(0xFF22C55E)
    val Good = Color(0xFF14B8A6)
    val Fair = Color(0xFFF59E0B)
    val Weak = Color(0xFFEF4444)
    val Info = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)

    val graph = listOf(
        Color(0xFF4FD8C4), Color(0xFF8AA4FF), Color(0xFFFFB86B), Color(0xFFF472B6), Color(0xFFA78BFA),
        Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFF60A5FA), Color(0xFFF87171), Color(0xFF2DD4BF),
    )
}

fun signalColor(rssi: Int): Color = when {
    rssi >= -55 -> NsColors.Excellent
    rssi >= -67 -> NsColors.Good
    rssi >= -75 -> NsColors.Fair
    else -> NsColors.Weak
}

fun latencyColor(ms: Double?): Color = when {
    ms == null -> NsColors.Weak
    ms < 20 -> NsColors.Excellent
    ms < 60 -> NsColors.Good
    ms < 150 -> NsColors.Fair
    else -> NsColors.Weak
}

fun graphColor(key: String): Color = NsColors.graph[(key.hashCode() and 0x7fffffff) % NsColors.graph.size]
