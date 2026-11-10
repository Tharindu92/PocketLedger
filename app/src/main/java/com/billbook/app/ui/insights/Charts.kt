package com.billbook.app.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Categorical colours, assigned in this fixed order (validated for colour-blind separation of
 * neighbours). Light and dark steps differ so both read against their surface.
 */
private val SERIES_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6)
private val SERIES_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9)

/** How many categories get their own colour; the rest share [otherColor]. */
const val COLOURED_CATEGORIES = 7

@Composable
fun seriesColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return (if (dark) SERIES_DARK else SERIES_LIGHT).map { Color(it) }
}

@Composable
fun otherColor(): Color = MaterialTheme.colorScheme.outline

@Composable
fun savingsColor(): Color = seriesColors().first()

/** Ring of slices; [values] are shares that add up to about 1. A 2dp surface gap separates slices. */
@Composable
fun DonutChart(values: List<Pair<Float, Color>>, size: Dp, modifier: Modifier = Modifier, center: @Composable () -> Unit = {}) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.16f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            var start = -90f
            for ((share, color) in values) {
                val sweep = share * 360f
                drawArc(color, start, sweep, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
                start += sweep
            }
            // Gaps between slices, drawn as surface-coloured spokes.
            if (values.size > 1) {
                start = -90f
                val r = this.size.minDimension / 2
                for ((share, _) in values) {
                    val a = Math.toRadians(start.toDouble())
                    val c = Offset(this.size.width / 2, this.size.height / 2)
                    drawLine(
                        surface,
                        c + Offset((kotlin.math.cos(a) * (r - stroke - 2)).toFloat(), (kotlin.math.sin(a) * (r - stroke - 2)).toFloat()),
                        c + Offset((kotlin.math.cos(a) * (r + 2)).toFloat(), (kotlin.math.sin(a) * (r + 2)).toFloat()),
                        strokeWidth = 2.dp.toPx(),
                    )
                    start += share * 360f
                }
            }
        }
        center()
    }
}

/**
 * Income and expense bar per month, side by side, from a shared baseline. Tapping a month selects it.
 */
@Composable
fun MonthBars(
    income: List<Long>,
    expense: List<Long>,
    incomeColor: Color,
    expenseColor: Color,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val count = income.size
    Canvas(
        modifier.fillMaxWidth().height(160.dp).pointerInput(count) {
            detectTapGestures { p -> if (count > 0) onSelect((p.x / (size.width / count)).toInt().coerceIn(0, count - 1)) }
        }
    ) {
        if (count == 0) return@Canvas
        val top = max(1L, max(income.maxOrNull() ?: 0, expense.maxOrNull() ?: 0)).toFloat()
        val slot = size.width / count
        val barWidth = min(slot * 0.32f, 18.dp.toPx())
        val gap = 2.dp.toPx()
        val baseline = size.height - 1.dp.toPx()
        drawGrid(grid, baseline)
        for (i in 0 until count) {
            val left = slot * i
            if (i == selected) drawRect(highlight, Offset(left, 0f), Size(slot, baseline))
            val centre = left + slot / 2
            drawBar(centre - gap / 2 - barWidth, barWidth, income[i] / top * baseline * 0.95f, baseline, incomeColor)
            drawBar(centre + gap / 2, barWidth, expense[i] / top * baseline * 0.95f, baseline, expenseColor)
        }
    }
}

private fun DrawScope.drawGrid(color: Color, baseline: Float) {
    for (f in listOf(0.25f, 0.5f, 0.75f)) {
        val y = baseline * (1 - f * 0.95f)
        drawLine(color.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
    drawLine(color, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
}

/** Bar rising from the baseline, rounded only at its top end. */
private fun DrawScope.drawBar(left: Float, width: Float, height: Float, baseline: Float, color: Color) {
    if (height <= 0f) return
    val radius = min(4.dp.toPx(), width / 2)
    val h = max(height, radius)
    drawRoundRect(color, Offset(left, baseline - h), Size(width, h), CornerRadius(radius))
    // Square off the bottom corners so the bar sits on the baseline.
    drawRect(color, Offset(left, baseline - min(h, radius)), Size(width, min(h, radius)))
}

/**
 * Running savings at the end of each month: a 2dp line with a light area towards zero and a
 * dashed zero line when savings go negative. Tapping selects the nearest month.
 */
@Composable
fun SavingsLine(values: List<Long>, color: Color, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val count = values.size
    Canvas(
        modifier.fillMaxWidth().height(160.dp).pointerInput(count) {
            detectTapGestures { p ->
                if (count > 0) {
                    val step = if (count == 1) size.width.toFloat() else size.width / (count - 1f)
                    onSelect(((p.x + step / 2) / step).toInt().coerceIn(0, count - 1))
                }
            }
        }
    ) {
        if (count == 0) return@Canvas
        val pad = 6.dp.toPx()
        val hi = max(0L, values.max()).toFloat()
        val lo = min(0L, values.min()).toFloat()
        val span = max(1f, hi - lo)
        fun x(i: Int) = if (count == 1) size.width / 2 else pad + (size.width - 2 * pad) * i / (count - 1)
        fun y(v: Float) = pad + (size.height - 2 * pad) * (1 - (v - lo) / span)
        val zero = y(0f)
        drawGrid(grid, size.height - 1.dp.toPx())
        if (lo < 0) {
            drawLine(grid, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v.toFloat())) else lineTo(x(i), y(v.toFloat())) }
        }
        if (count > 1) {
            val area = Path().apply {
                addPath(line)
                lineTo(x(count - 1), zero)
                lineTo(x(0), zero)
                close()
            }
            drawPath(area, color.copy(alpha = 0.14f))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        val marker = selected ?: (count - 1)
        val p = Offset(x(marker), y(values[marker].toFloat()))
        if (selected != null) {
            drawLine(grid, Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1.dp.toPx())
        }
        drawCircle(surface, 6.dp.toPx(), p)
        drawCircle(color, 4.dp.toPx(), p)
    }
}
