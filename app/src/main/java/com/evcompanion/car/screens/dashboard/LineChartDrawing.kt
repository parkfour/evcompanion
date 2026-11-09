package com.evcompanion.car.screens.dashboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.evcompanion.core.theme.DashboardTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A readable time-series chart for the full-screen detail view: legend with live values, a real
 * time X axis, and up to three labelled Y axes picked by unit (first unit left, the next two on
 * the right). A fourth unit would be scaled to its own range and only read off the legend.
 */
object LineChartDrawing {

    data class Series(val label: String, val color: Int, val unit: String, val samples: List<Pair<Long, Double>>)

    fun draw(
        canvas: Canvas,
        rect: Rect,
        title: String,
        series: List<Series>,
        windowSeconds: Int,
        nowMillis: Long,
        theme: DashboardTheme,
    ) {
        val text = (rect.height() * 0.055f).coerceIn(12f, 26f)
        val pad = text * 0.6f
        val titlePaint = paint(theme.headingColor, text * 1.1f).apply { typeface = theme.labelTypeface }
        canvas.drawText(title, rect.left + pad, rect.top + pad + text, titlePaint)

        val legendBottom = drawLegend(canvas, rect, rect.top + pad * 2 + text * 1.3f, text, pad, series, theme)

        val axisOf = assignAxes(series.map { s -> s.unit.takeIf { s.samples.isNotEmpty() } })
        val axisTicks = (0 until MAX_AXES).map { axis ->
            val values = series.filterIndexed { i, _ -> axisOf[i] == axis }.flatMap { s -> s.samples.map { it.second } }
            if (values.isEmpty()) null else niceTicks(values.min(), values.max())
        }
        val labelPaint = paint(theme.textSecondaryColor, text * 0.8f)
        val axisLabelW = labelPaint.measureText("-000.0") + pad
        val plot = RectF(
            rect.left + if (axisTicks[0] != null) axisLabelW else pad,
            legendBottom + pad,
            rect.right - maxOf(pad, axisTicks.drop(1).count { it != null } * axisLabelW),
            rect.bottom - text * 1.8f,
        )
        if (plot.width() < 20f || plot.height() < 20f) return

        axisTicks.forEachIndexed { axis, ticks -> drawYAxis(canvas, plot, ticks, series, axisOf, axis, labelPaint, axisLabelW, theme) }
        drawTimeAxis(canvas, plot, windowSeconds, labelPaint, text)

        val startMillis = nowMillis - windowSeconds * 1000L
        series.forEachIndexed { i, s ->
            if (s.samples.size < 2) return@forEachIndexed
            val ticks = axisOf[i]?.let { axisTicks[it] }
            val (lo, hi) = if (ticks != null) ticks.first() to ticks.last() else rangeOf(s.samples.map { it.second })
            val path = Path()
            s.samples.forEachIndexed { j, (t, v) ->
                val x = plot.left + plot.width() * ((t - startMillis).toFloat() / (windowSeconds * 1000f)).coerceIn(0f, 1f)
                val y = plot.bottom - plot.height() * ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint(s.color, 0f).apply { style = Paint.Style.STROKE; strokeWidth = (text * 0.18f).coerceAtLeast(2f) })
        }
    }

    /** Legend entries flow left to right, wrapping; returns the y below the last row. */
    private fun drawLegend(canvas: Canvas, rect: Rect, top: Float, text: Float, pad: Float, series: List<Series>, theme: DashboardTheme): Float {
        val p = paint(theme.textPrimaryColor, text)
        val swatch = text * 0.7f
        var x = rect.left + pad
        var baseline = top + text
        series.forEach { s ->
            val label = "${s.label} ${formatValue(s.samples.lastOrNull()?.second, s.unit)}"
            val w = swatch + pad * 0.5f + p.measureText(label) + pad * 1.5f
            if (x + w > rect.right && x > rect.left + pad) {
                x = rect.left + pad
                baseline += text * 1.4f
            }
            canvas.drawRect(x, baseline - swatch, x + swatch, baseline, paint(s.color, 0f))
            canvas.drawText(label, x + swatch + pad * 0.5f, baseline, p)
            x += w
        }
        return baseline + text * 0.4f
    }

    private fun drawYAxis(
        canvas: Canvas, plot: RectF, ticks: List<Double>?, series: List<Series>, axisOf: List<Int?>, axis: Int,
        labelPaint: Paint, axisLabelW: Float, theme: DashboardTheme,
    ) {
        if (ticks == null) return
        val lo = ticks.first()
        val hi = ticks.last()
        val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
        val first = series.filterIndexed { i, _ -> axisOf[i] == axis }.firstOrNull()
        val p = Paint(labelPaint).apply {
            color = first?.color ?: theme.textSecondaryColor
            textAlign = if (axis == 0) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val grid = paint(theme.graphGridColor ?: theme.textSecondaryColor, 0f).apply { style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 70 }
        // Axis 0 sits left of the plot; axes 1, 2 stack outward on the right.
        val x = if (axis == 0) plot.left - labelPaint.textSize * 0.4f
        else plot.right + labelPaint.textSize * 0.4f + (axis - 1) * axisLabelW
        ticks.forEach { v ->
            val y = plot.bottom - plot.height() * ((v - lo) / (hi - lo)).toFloat()
            if (axis == 0) canvas.drawLine(plot.left, y, plot.right, y, grid)
            canvas.drawText(formatTick(v, step), x, y + labelPaint.textSize * 0.35f, p)
        }
        canvas.drawText(first?.unit?.let(::displayUnit) ?: "", x, plot.top - labelPaint.textSize * 0.6f, p)
    }

    private fun drawTimeAxis(canvas: Canvas, plot: RectF, windowSeconds: Int, labelPaint: Paint, text: Float) {
        val step = TIME_STEPS_SECONDS.firstOrNull { windowSeconds / it <= 6 } ?: windowSeconds
        val p = Paint(labelPaint).apply { textAlign = Paint.Align.CENTER }
        val axis = Paint(labelPaint).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axis)
        var ago = 0
        while (ago <= windowSeconds) {
            val x = plot.right - plot.width() * ago / windowSeconds
            canvas.drawLine(x, plot.bottom, x, plot.bottom + text * 0.3f, axis)
            canvas.drawText(formatAgo(ago), x, plot.bottom + text * 1.4f, p)
            ago += step
        }
    }

    private fun paint(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size }

    private fun rangeOf(values: List<Double>): Pair<Double, Double> {
        val lo = values.min()
        val hi = values.max()
        return if (hi - lo < 1e-9) (lo - 1) to (hi + 1) else lo to hi
    }

    private val TIME_STEPS_SECONDS = listOf(10, 30, 60, 120, 300, 600, 1800, 3600)

    // ---- pure helpers (unit-tested) ----

    private const val MAX_AXES = 3

    /** Distinct units in order -> axis 0 (left), 1 and 2 (right); a 4th unit (or no data) -> null. */
    fun assignAxes(units: List<String?>): List<Int?> {
        val order = units.filterNotNull().distinct()
        return units.map { u -> u?.let { order.indexOf(it) }?.takeIf { it in 0 until MAX_AXES } }
    }

    /** Round tick values covering [min]..[max], e.g. 12..302 -> 0, 100, 200, 300, 400. */
    fun niceTicks(min: Double, max: Double, target: Int = 4): List<Double> {
        val (lo, hi) = if (max - min < 1e-9) (min - 1) to (max + 1) else min to max
        val raw = (hi - lo) / target
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw * 0.999 }
        val start = floor(lo / step) * step
        val end = ceil(hi / step) * step
        return generateSequence(start) { it + step }.takeWhile { it <= end + step * 1e-6 }.toList()
    }

    fun formatTick(v: Double, step: Double): String =
        if (step >= 1.0) "%.0f".format(v) else "%.1f".format(v)

    fun formatAgo(seconds: Int): String = when {
        seconds == 0 -> "now"
        seconds % 60 == 0 -> "-${seconds / 60}m"
        else -> "-${seconds}s"
    }

    fun displayUnit(unit: String): String = if (unit == "C") "°C" else unit

    fun formatValue(value: Double?, unit: String): String {
        if (value == null) return "--"
        val decimals = if (unit == "%") 0 else 1 // matches the dashboard tiles
        return "%.${decimals}f %s".format(value, displayUnit(unit)).trim()
    }
}
