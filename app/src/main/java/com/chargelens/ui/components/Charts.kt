package com.chargelens.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargelens.core.Fmt
import com.chargelens.ui.TrendPoint
import java.util.Locale
import kotlin.math.roundToInt

private fun formatNumber(value: Float): String = when {
    value >= 100f -> String.format(Locale.US, "%.0f", value)
    value >= 10f -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.2f", value)
}

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (width <= 0 || count < 2) return -1
    val ratio = (x / width).coerceIn(0f, 1f)
    return (ratio * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/** Big arc gauge used as the hero element of the live screen. */
@Composable
fun PowerDial(
    powerW: Float,
    maxW: Float,
    statusText: String,
    subText: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val target = (powerW / maxW.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(targetValue = target, animationSpec = tween(600), label = "dial")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier
            .fillMaxWidth()
            .height(248.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(238.dp)) {
            val stroke = 15.dp.toPx()
            val inset = stroke / 2f + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (fraction > 0.004f) {
                drawArc(
                    color = accent.copy(alpha = 0.18f),
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 2.1f, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.75f), Color(0xFFFFC24B)),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Fmt.watts(powerW),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = onSurface
                )
                Text(
                    text = " W",
                    fontSize = 17.sp,
                    color = onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 9.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(text = statusText, style = MaterialTheme.typography.titleMedium, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(text = subText, style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant)
        }
    }
}

/**
 * Lightweight canvas chart: area + smoothed line + tap/drag crosshair.
 * Implemented in-app so the project keeps zero chart dependencies.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    emptyText: String = "暂无数据"
) {
    if (points.size < 2) {
        Box(
            modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val values = points.map { it.value }
    val rawMin = values.minOrNull() ?: 0f
    val rawMax = values.maxOrNull() ?: 1f
    val span = (rawMax - rawMin).takeIf { it > 0.001f } ?: (if (rawMax > 0.001f) rawMax * 0.25f else 1f)
    val lo = rawMin - span * 0.15f
    val hi = rawMax + span * 0.15f
    val range = (hi - lo).coerceAtLeast(0.001f)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember(points.size) { mutableIntStateOf(-1) }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("峰值 ${formatNumber(rawMax)} $unit", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("最低 ${formatNumber(rawMin)} $unit", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { canvasSize = it }
                .pointerInput(points.size) {
                    detectTapGestures { offset ->
                        cursor = indexAt(offset.x, size.width, points.size)
                    }
                }
                .pointerInput(points.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> cursor = indexAt(offset.x, size.width, points.size) },
                        onHorizontalDrag = { change, _ ->
                            cursor = indexAt(change.position.x, size.width, points.size)
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }

                val stepX = w / (values.size - 1).toFloat()
                fun px(index: Int) = stepX * index
                fun py(value: Float) = h - ((value - lo) / range) * h

                val line = Path()
                line.moveTo(px(0), py(values[0]))
                for (i in 1 until values.size) {
                    val midX = (px(i - 1) + px(i)) / 2f
                    line.cubicTo(midX, py(values[i - 1]), midX, py(values[i]), px(i), py(values[i]))
                }

                val fill = Path().apply {
                    addPath(line)
                    lineTo(px(values.size - 1), h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        colors = listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0f)),
                        startY = 0f,
                        endY = h
                    )
                )
                drawPath(
                    path = line,
                    color = accent,
                    style = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                val lastIndex = values.size - 1
                drawCircle(accent.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = Offset(px(lastIndex), py(values[lastIndex])))
                drawCircle(accent, radius = 3.6.dp.toPx(), center = Offset(px(lastIndex), py(values[lastIndex])))

                if (cursor in values.indices) {
                    val x = px(cursor)
                    drawLine(
                        color = accent.copy(alpha = 0.55f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                    drawCircle(accent, radius = 4.5.dp.toPx(), center = Offset(x, py(values[cursor])))
                }
            }

            if (cursor in points.indices && canvasSize.width > 0) {
                val tipWidth = 156.dp
                val x = canvasSize.width.toFloat() * cursor / (points.size - 1).toFloat()
                Box(
                    Modifier
                        .offset {
                            val half = tipWidth.toPx() / 2f
                            val maxX = (canvasSize.width - tipWidth.toPx()).coerceAtLeast(0f)
                            IntOffset((x - half).coerceIn(0f, maxX).toInt(), 0)
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${Fmt.time(points[cursor].ts)} · ${formatNumber(points[cursor].value)} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
