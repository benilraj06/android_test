package dev.netscope.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.netscope.net.DeviceType
import dev.netscope.ui.theme.NsColors

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = CardShape, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier, shape = CardShape, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun GradientCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.secondaryContainer))),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.7f, center = Offset(size.width * 0.98f, size.height * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension * 0.45f, center = Offset(size.width * 0.05f, size.height * 1.05f))
        }
        CompositionLocalProvider(LocalContentColor provides cs.onPrimaryContainer) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.33f))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    AppCard(modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        IconBadge(icon, tint)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(
                sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        action?.invoke()
    }
}

@Composable
fun InfoRow(label: String, value: String?, mono: Boolean = false, copyable: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val clickable = if (copyable && value != null) {
        Modifier.clickable {
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
    } else Modifier
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(clickable).padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.width(128.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: "—",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono) FontFamily.Monospace else null),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.ROUTER -> Icons.Filled.Router
    DeviceType.PHONE -> Icons.Filled.Smartphone
    DeviceType.TABLET -> Icons.Filled.Tablet
    DeviceType.COMPUTER -> Icons.Filled.Computer
    DeviceType.TV -> Icons.Filled.Tv
    DeviceType.SPEAKER -> Icons.Filled.Speaker
    DeviceType.PRINTER -> Icons.Filled.Print
    DeviceType.CAMERA -> Icons.Filled.Videocam
    DeviceType.NAS -> Icons.Filled.Storage
    DeviceType.CONSOLE -> Icons.Filled.SportsEsports
    DeviceType.IOT -> Icons.Filled.Lightbulb
    DeviceType.UNKNOWN -> Icons.Filled.DevicesOther
}

fun DeviceType.tint(): Color = when (this) {
    DeviceType.ROUTER -> Color(0xFF14B8A6)
    DeviceType.PHONE, DeviceType.TABLET -> NsColors.Info
    DeviceType.COMPUTER -> NsColors.Violet
    DeviceType.TV -> NsColors.Pink
    DeviceType.SPEAKER -> Color(0xFFF97316)
    DeviceType.PRINTER -> Color(0xFF64748B)
    DeviceType.CAMERA -> NsColors.Weak
    DeviceType.NAS -> Color(0xFF0EA5E9)
    DeviceType.CONSOLE -> Color(0xFF10B981)
    DeviceType.IOT -> NsColors.Fair
    DeviceType.UNKNOWN -> Color(0xFF94A3B8)
}

@Composable
fun DeviceAvatar(type: DeviceType, size: Dp = 44.dp, online: Boolean = true) {
    val c = type.tint()
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(c.copy(alpha = if (online) 0.18f else 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(type.icon(), null, tint = if (online) c else c.copy(alpha = 0.45f), modifier = Modifier.size(size * 0.52f))
    }
}

/** Standard scaffold for detail / tool screens with a back arrow. */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { actions() },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        content = content,
    )
}

fun timeAgo(ts: Long?): String {
    if (ts == null || ts == 0L) return "never"
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
