package com.evcompanion.car.screens.dashboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.evcompanion.core.theme.DashboardTheme
import com.evcompanion.core.theme.DefaultDashboardTheme
import com.evcompanion.core.theme.GaugeStyle

/**
 * Shared Canvas-drawing primitives for the hand-drawn dashboard pages (see DashboardScreen).
 * Plain android.graphics drawing, parameterized by [DashboardTheme] to support the luminous
 * modern EV Dashboard theme as well as clean minimalist flat themes.
 */
object GaugeDrawing {

    /** A circular progress ring or swept gauge with a bold value string centered inside it and a label. */
    fun drawBigGauge(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        fraction: Double?,
        valueText: String,
        label: String,
        ringColor: (Double) -> Int = ::defaultGaugeColor,
        theme: DashboardTheme = DefaultDashboardTheme,
    ) {
        val strokeWidth = radius * theme.gaugeStrokeRatio
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            color = theme.gaugeTrackColor
        }

        val isSwept = theme.gaugeStyle == GaugeStyle.SWEPT_ARC_270
        val startAngle = if (isSwept) 135f else -90f
        val maxSweep = if (isSwept) 270f else 360f

        canvas.drawArc(rect, startAngle, maxSweep, false, trackPaint)

        // Draw radial tick marks for swept automotive gauge style
        theme.gaugeTickColor?.let { tickColor ->
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = (strokeWidth * 0.22f).coerceIn(1.5f, 3.5f)
                color = tickColor
                strokeCap = Paint.Cap.ROUND
            }
            val tickInnerR = radius - strokeWidth * 0.65f
            val tickOuterR = radius - strokeWidth * 0.92f
            for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val angleRad = Math.toRadians(startAngle.toDouble() + t * maxSweep.toDouble())
                val cosA = Math.cos(angleRad).toFloat()
                val sinA = Math.sin(angleRad).toFloat()
                canvas.drawLine(
                    centerX + tickInnerR * cosA,
                    centerY + tickInnerR * sinA,
                    centerX + tickOuterR * cosA,
                    centerY + tickOuterR * sinA,
                    tickPaint,
                )
            }
        }

        if (fraction != null) {
            val sweep = fraction.coerceIn(0.0, 1.0) * maxSweep
            val arcColor = ringColor(fraction)
            val fillPaint = Paint(trackPaint).apply { color = arcColor }
            canvas.drawArc(rect, startAngle, sweep.toFloat(), false, fillPaint)

            // Illuminated needle tip pip / glowing cap if configured by theme
            theme.gaugeNeedlePipColor?.let { pipColor ->
                if (sweep > 1.5f) {
                    val endAngleRad = Math.toRadians(startAngle.toDouble() + sweep.toDouble())
                    val tipX = centerX + radius * Math.cos(endAngleRad).toFloat()
                    val tipY = centerY + radius * Math.sin(endAngleRad).toFloat()

                    // Glow halo inheriting the current arc color
                    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = Color.argb(90, Color.red(arcColor), Color.green(arcColor), Color.blue(arcColor))
                    }
                    val haloR = strokeWidth * 0.85f
                    canvas.drawCircle(tipX, tipY, haloR, haloPaint)

                    // Crisp needle head pip
                    val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = pipColor
                    }
                    val pipR = strokeWidth * 0.46f
                    canvas.drawCircle(tipX, tipY, pipR, pipPaint)
                }
            }
        }

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textPrimaryColor
            textAlign = Paint.Align.CENTER
            textSize = radius * 0.5f
            typeface = theme.valueTypeface
        }
        valuePaint.textSize = shrinkToFit(valuePaint, valueText, radius * 1.55f)
        val valY = if (isSwept) centerY + valuePaint.textSize * 0.20f else centerY + valuePaint.textSize * 0.35f
        canvas.drawText(valueText, centerX, valY, valuePaint)

        val labelPaint = Paint(valuePaint).apply {
            textSize = radius * 0.15f
            typeface = theme.labelTypeface
            color = theme.headingColor
        }
        labelPaint.textSize = shrinkToFit(labelPaint, label, radius * 1.55f)
        val lblY = if (isSwept) centerY + radius * 0.60f else centerY - radius * 0.4f
        canvas.drawText(label, centerX, lblY, labelPaint)
    }

    fun defaultGaugeColor(fraction: Double): Int = DefaultDashboardTheme.resolveGaugeColor(fraction)

    /**
     * A label above or below a value string, centered at ([centerX], [centerY]), sized off [textSize] but
     * shrunk to fit within [maxWidth].
     */
    fun drawReadout(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        textSize: Float,
        label: String,
        valueText: String,
        maxWidth: Float = Float.MAX_VALUE,
        theme: DashboardTheme = DefaultDashboardTheme,
        // 1 (default) = fully opaque. Used to fade a value out in place (e.g. the Motor RPM
        // tile's front cell once the vehicle is found to be RWD-only) without changing layout.
        alpha: Float = 1f,
    ) {
        fun withAlpha(color: Int): Int =
            Color.argb((255 * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(theme.textPrimaryColor)
            textAlign = Paint.Align.CENTER
            this.textSize = textSize
            typeface = theme.valueTypeface
        }
        valuePaint.textSize = shrinkToFit(valuePaint, valueText, maxWidth)
        canvas.drawText(valueText, centerX, centerY, valuePaint)

        val labelPaint = Paint(valuePaint).apply {
            this.textSize = textSize * 0.45f
            color = withAlpha(theme.textSecondaryColor)
            typeface = theme.labelTypeface
        }
        labelPaint.textSize = shrinkToFit(labelPaint, label, maxWidth)
        canvas.drawText(label, centerX, centerY + textSize * 0.6f, labelPaint)
    }

    /** Shrinks [paint]'s already-set text size so [text] measures within [maxWidth]; never grows it. */
    fun shrinkToFit(paint: Paint, text: String, maxWidth: Float): Float {
        if (text.isEmpty() || maxWidth <= 0f) return paint.textSize
        val measured = paint.measureText(text)
        return if (measured > maxWidth) paint.textSize * (maxWidth / measured) else paint.textSize
    }

    /** A rounded-rect tile card: [fillColor] fill (with optional vertical gradient depth) and theme border. */
    fun drawCard(
        canvas: Canvas,
        rect: Rect,
        fillColor: Int,
        stroke: Paint? = null,
        theme: DashboardTheme = DefaultDashboardTheme,
    ) {
        val radius = minOf(rect.width(), rect.height()) * theme.cardCornerRadiusRatio
        val r = RectF(rect)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        val bottomColor = theme.cardGradientBottomColor
        if (bottomColor != null && stroke == null && fillColor == theme.defaultCardColor) {
            fill.shader = LinearGradient(
                0f, r.top,
                0f, r.bottom,
                fillColor,
                bottomColor,
                Shader.TileMode.CLAMP,
            )
        } else {
            fill.color = fillColor
        }
        canvas.drawRoundRect(r, radius, radius, fill)

        if (stroke != null) {
            canvas.drawRoundRect(r, radius, radius, stroke)
        } else {
            theme.cardBorderColor?.let { borderColor ->
                val strokeWidth = (minOf(rect.width(), rect.height()) * 0.015f).coerceIn(1.5f, 3.5f)
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    color = borderColor
                }
                val half = strokeWidth / 2f
                val strokeRect = RectF(r.left + half, r.top + half, r.right - half, r.bottom - half)
                canvas.drawRoundRect(strokeRect, radius - half, radius - half, borderPaint)
            }
        }
    }

    /** A large centered value with a small label - the hero/primary readout. */
    fun drawBigReadout(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        width: Float,
        label: String,
        valueText: String,
        theme: DashboardTheme = DefaultDashboardTheme,
    ) {
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textPrimaryColor
            textAlign = Paint.Align.CENTER
            typeface = theme.valueTypeface
            textSize = (width * 0.14f).coerceAtLeast(28f)
        }
        valuePaint.textSize = shrinkToFit(valuePaint, valueText, width * 0.92f)

        val labelPaint = Paint(valuePaint).apply {
            textSize = valuePaint.textSize * 0.4f
            typeface = theme.labelTypeface
            color = theme.headingColor
        }
        labelPaint.textSize = shrinkToFit(labelPaint, label, width * 0.92f)

        // Stack label above value using actual font metrics rather than a fixed fraction of the
        // value's text size - a fixed fraction left big values (where label is a small fraction
        // of a very large valueText) with far too little gap, badly overlapping the two lines.
        val valueMetrics = valuePaint.fontMetrics
        val labelMetrics = labelPaint.fontMetrics
        val valueBlockHeight = valueMetrics.descent - valueMetrics.ascent
        val labelBlockHeight = labelMetrics.descent - labelMetrics.ascent
        val gap = valuePaint.textSize * 0.08f
        val totalHeight = labelBlockHeight + gap + valueBlockHeight
        val top = centerY - totalHeight / 2f
        val labelBaseline = top - labelMetrics.ascent
        val valueBaseline = top + labelBlockHeight + gap - valueMetrics.ascent

        canvas.drawText(label, centerX, labelBaseline, labelPaint)
        canvas.drawText(valueText, centerX, valueBaseline, valuePaint)
    }

    /** One cell's bounds within a [gridCells] layout. */
    data class GridCell(val rect: Rect, val cx: Float, val cy: Float)

    /** Divides [area] into [count] cell rects - stacked vertically for 2-3 items, a compact grid beyond that. */
    fun gridCells(area: Rect, count: Int): List<GridCell> {
        if (count <= 0) return emptyList()
        val columns = when {
            count <= 3 -> 1
            count == 4 -> 2
            else -> 3
        }
        val rows = (count + columns - 1) / columns
        val cellWidth = area.width().toFloat() / columns
        val cellHeight = area.height().toFloat() / rows
        return (0 until count).map { i ->
            val col = i % columns
            val row = i / columns
            val left = area.left + cellWidth * col
            val top = area.top + cellHeight * row
            val rect = Rect(left.toInt(), top.toInt(), (left + cellWidth).toInt(), (top + cellHeight).toInt())
            GridCell(rect, left + cellWidth / 2f, top + cellHeight / 2f)
        }
    }

    fun formatValue(value: Double?, unit: String, decimals: Int = 1): String =
        if (unit.equals("REGEN", ignoreCase = true)) {
            "REGEN"
        } else {
            value?.let { "%.${decimals}f %s".format(it, unit).trim() } ?: "-- $unit".trim()
        }

    /**
     * A label above overlaid line series, Y-autoscaled to the data range.
     * Optionally draws subtle grid lines, smooth cubic Bezier curves, and gradient glowing area fill.
     */
    fun drawLineGraph(
        canvas: Canvas,
        rect: Rect,
        label: String,
        series: List<Pair<List<Float>, Int>>,
        theme: DashboardTheme = DefaultDashboardTheme,
        // False (default): every series scaled against one shared min/max - correct when they
        // share a unit (e.g. MIN/MAX pack temp both in C). True: each series scaled against its
        // own min/max instead - for overlaying differently-scaled metrics (SoC %, power kW,
        // current A, temp C) so none of them get flattened by the others' range.
        perSeriesNormalize: Boolean = false,
    ) {
        val headingSize = rect.height() * 0.08f
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.headingColor
            textAlign = Paint.Align.CENTER
            textSize = headingSize
            typeface = theme.labelTypeface
        }
        canvas.drawText(label, rect.exactCenterX(), rect.top + headingSize * 1.3f, headingPaint)

        val graphRect = Rect(rect.left, (rect.top + headingSize * 1.9f).toInt(), rect.right, rect.bottom)
        val allValues = series.flatMap { it.first }
        if (allValues.size < 2 || graphRect.height() <= 0) return

        fun rangeOf(values: List<Float>): Pair<Float, Float> {
            if (values.isEmpty()) return 0f to 1f
            val minV = values.min()
            val maxV = values.max()
            return minV to ((maxV - minV).takeIf { it > 0.5f } ?: 1f)
        }
        val sharedRange = rangeOf(allValues)
        val ranges = series.map { (values, _) -> if (perSeriesNormalize) rangeOf(values) else sharedRange }

        // Subtle reference grid lines if configured
        theme.graphGridColor?.let { gridColor ->
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gridColor
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val stepY = graphRect.height() / 3f
            canvas.drawLine(graphRect.left.toFloat(), graphRect.top + stepY, graphRect.right.toFloat(), graphRect.top + stepY, gridPaint)
            canvas.drawLine(graphRect.left.toFloat(), graphRect.top + stepY * 2, graphRect.right.toFloat(), graphRect.top + stepY * 2, gridPaint)
        }

        fun computePoints(values: List<Float>, minV: Float, range: Float): List<PointF> {
            val count = values.size
            return values.mapIndexed { i, v ->
                val x = graphRect.left + graphRect.width() * (i.toFloat() / (count - 1).coerceAtLeast(1))
                val y = (graphRect.bottom - ((v - minV) / range) * graphRect.height())
                    .coerceIn(graphRect.top.toFloat(), graphRect.bottom.toFloat())
                PointF(x, y)
            }
        }

        fun pathFor(points: List<PointF>, smooth: Boolean): Path {
            val path = Path()
            if (points.isEmpty()) return path
            path.moveTo(points[0].x, points[0].y)
            if (points.size == 1) return path

            if (!smooth || points.size < 3) {
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                return path
            }

            // Catmull-Rom to Cubic Bezier spline smoothing for fluid automotive curves
            val tension = 0.18f
            for (i in 0 until points.size - 1) {
                val p0 = if (i > 0) points[i - 1] else points[i]
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = if (i + 2 < points.size) points[i + 2] else p2

                val c1x = p1.x + (p2.x - p0.x) * tension
                val c1y = (p1.y + (p2.y - p0.y) * tension).coerceIn(graphRect.top.toFloat(), graphRect.bottom.toFloat())
                val c2x = p2.x - (p3.x - p1.x) * tension
                val c2y = (p2.y - (p3.y - p1.y) * tension).coerceIn(graphRect.top.toFloat(), graphRect.bottom.toFloat())

                path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
            }
            return path
        }

        // Draw translucent area fill under line if configured
        if (theme.graphAreaFillAlpha > 0) {
            series.forEachIndexed { i, (values, color) ->
                if (values.size >= 2) {
                    val (minV, range) = ranges[i]
                    val points = computePoints(values, minV, range)
                    val linePath = pathFor(points, theme.smoothGraphCurves)
                    val fillPath = Path(linePath).apply {
                        lineTo(graphRect.right.toFloat(), graphRect.bottom.toFloat())
                        lineTo(graphRect.left.toFloat(), graphRect.bottom.toFloat())
                        close()
                    }
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                    }
                    if (theme.graphAreaGradient) {
                        val topColor = Color.argb(
                            theme.graphAreaFillAlpha,
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color),
                        )
                        val bottomColor = Color.argb(
                            0,
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color),
                        )
                        fillPaint.shader = LinearGradient(
                            0f, graphRect.top.toFloat(),
                            0f, graphRect.bottom.toFloat(),
                            topColor,
                            bottomColor,
                            Shader.TileMode.CLAMP,
                        )
                    } else {
                        fillPaint.color = Color.argb(
                            theme.graphAreaFillAlpha,
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color),
                        )
                    }
                    canvas.drawPath(fillPath, fillPaint)
                }
            }
        }

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (graphRect.height() * 0.022f).coerceIn(2.0f, 4.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        series.forEachIndexed { i, (values, color) ->
            if (values.size >= 2) {
                val (minV, range) = ranges[i]
                val points = computePoints(values, minV, range)
                val linePath = pathFor(points, theme.smoothGraphCurves)
                canvas.drawPath(linePath, Paint(basePaint).apply { this.color = color })

                // Live beacon dot at latest data point
                if (theme.graphBeaconDot && points.isNotEmpty()) {
                    val last = points.last()
                    val dotRadius = (graphRect.height() * 0.045f).coerceIn(3.5f, 7.5f)

                    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = Color.argb(85, Color.red(color), Color.green(color), Color.blue(color))
                    }
                    canvas.drawCircle(last.x, last.y, dotRadius * 2.2f, haloPaint)

                    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = color
                    }
                    canvas.drawCircle(last.x, last.y, dotRadius, ringPaint)

                    val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = Color.WHITE
                    }
                    canvas.drawCircle(last.x, last.y, dotRadius * 0.45f, centerPaint)
                }
            }
        }
    }
}
