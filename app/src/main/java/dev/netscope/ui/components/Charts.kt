package dev.netscope.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.netscope.net.WifiBand
import dev.netscope.net.WifiNetwork
import dev.netscope.ui.theme.graphColor
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SignalBars(bars: Int, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.1f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (i + 1) / n
            drawRoundRect(
                color = if (i < bars) color else track,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3f),
            )
        }
    }
}

@Composable
fun RingGauge(
    fraction: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
    colors: List<Color> = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(600), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val d = size.minDimension - stroke
            val arcSize = Size(d, d)
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(
                color = trackColor, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (animated > 0.001f) {
                drawArc(
                    brush = Brush.linearGradient(colors),
                    startAngle = 135f, sweepAngle = 270f * animated, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    minValue: Float? = null,
    maxValue: Float? = null,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = minValue ?: values.min()
        val rawMax = maxValue ?: values.max()
        val max = if (rawMax - min < 0.001f) min + 1f else rawMax
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = (size.height - (v - min) / (max - min) * size.height).coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun RadarSweep(active: Boolean, blips: List<String>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "angle",
    )
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        for (i in 1..3) drawCircle(color.copy(alpha = 0.2f), radius = r * i / 3f, center = c, style = Stroke(1.dp.toPx()))
        drawLine(color.copy(alpha = 0.12f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(color.copy(alpha = 0.12f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        if (active) {
            rotate(angle, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent, 0.7f to Color.Transparent, 1f to color.copy(alpha = 0.5f),
                        center = c,
                    ),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                )
            }
        }
        blips.forEach { ip ->
            val h = ip.hashCode() and 0x7fffffff
            val a = Math.toRadians((h % 360).toDouble())
            val last = ip.substringAfterLast('.').toIntOrNull() ?: 0
            val dist = r * (0.2f + 0.72f * ((last * 37 % 100) / 100f))
            val p = Offset(c.x + (dist * cos(a)).toFloat(), c.y + (dist * sin(a)).toFloat())
            drawCircle(color.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = p)
            drawCircle(color, radius = 3.dp.toPx(), center = p)
        }
        drawCircle(color, radius = 4.dp.toPx(), center = c)
    }
}

@Composable
fun ChannelGraph(networks: List<WifiNetwork>, band: WifiBand, connectedBssid: String?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val minCh: Int
    val maxCh: Int
    val ticks: List<Int>
    when (band) {
        WifiBand.GHZ_5 -> { minCh = 30; maxCh = 179; ticks = listOf(36, 52, 100, 116, 132, 149, 165) }
        WifiBand.GHZ_6 -> { minCh = -3; maxCh = 237; ticks = listOf(1, 33, 65, 97, 129, 161, 193, 225) }
        else -> { minCh = -1; maxCh = 15; ticks = (1..13).toList() }
    }
    val inBand = networks.filter { it.band == band }.sortedBy { it.level }
    Canvas(modifier) {
        val left = 30.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val top = 6.dp.toPx()
        val w = size.width - left
        val h = size.height - bottomPad - top
        val baseline = top + h
        fun x(ch: Float) = left + (ch - minCh) / (maxCh - minCh) * w
        fun y(dbm: Int) = top + h * (1f - ((dbm.coerceIn(-100, -20) + 100) / 80f))

        listOf(-30, -50, -70, -90).forEach { d ->
            val yy = y(d)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val layout = measurer.measure("$d", labelStyle)
            drawText(layout, topLeft = Offset(0f, yy - layout.size.height / 2f))
        }
        drawLine(grid, Offset(left, baseline), Offset(size.width, baseline), strokeWidth = 1.5f)
        ticks.forEach { ch ->
            val layout = measurer.measure("$ch", labelStyle)
            drawText(layout, topLeft = Offset(x(ch.toFloat()) - layout.size.width / 2f, baseline + 4.dp.toPx()))
        }
        inBand.forEach { n ->
            val color = graphColor(n.bssid)
            val isConn = n.bssid.equals(connectedBssid, ignoreCase = true)
            val half = n.widthMhz / 10f
            val xl = x(n.centerChannel - half)
            val xr = x(n.centerChannel + half)
            val yp = y(n.level)
            val ctrlY = baseline - (baseline - yp) / 0.75f
            val path = Path().apply {
                moveTo(xl, baseline)
                cubicTo(xl + (xr - xl) * 0.12f, ctrlY, xr - (xr - xl) * 0.12f, ctrlY, xr, baseline)
            }
            drawPath(path, color.copy(alpha = if (isConn) 0.32f else 0.13f))
            drawPath(path, color, style = Stroke(if (isConn) 3.dp.toPx() else 1.5.dp.toPx()))
            val layout = measurer.measure(
                n.displaySsid,
                labelStyle.copy(color = color, fontWeight = if (isConn) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
            )
            val maxX = (size.width - layout.size.width).coerceAtLeast(left)
            drawText(
                layout,
                topLeft = Offset(
                    ((xl + xr) / 2f - layout.size.width / 2f).coerceIn(left, maxX),
                    (yp - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        }
    }
}
