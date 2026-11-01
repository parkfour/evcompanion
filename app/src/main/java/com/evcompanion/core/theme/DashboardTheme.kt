package com.evcompanion.core.theme

import android.graphics.Typeface

enum class DashboardThemeId {
    EV_DASHBOARD,
    IONIQ5_COMPANION,
    CLASSIC_DARK;

    companion object {
        fun fromString(value: String?): DashboardThemeId = when (value?.uppercase()) {
            "EV_DASHBOARD", "EV", "LUMINOUS", "DEFAULT" -> EV_DASHBOARD
            "IONIQ5_COMPANION", "IONIQ_5", "IONIQ5" -> IONIQ5_COMPANION
            "CLASSIC_DARK", "CLASSIC", "DARK" -> CLASSIC_DARK
            else -> EV_DASHBOARD
        }
    }
}

enum class GaugeStyle {
    FULL_RING,
    SWEPT_ARC_270,
}

// Bitwise color helper functions matching android.graphics.Color integer representations,
// ensuring exact values in both Android runtime and local JVM unit tests without stubbing issues.
internal fun colorRgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

internal fun colorArgb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

interface DashboardTheme {
    val id: DashboardThemeId
    val displayName: String

    val backgroundColor: Int
    val defaultCardColor: Int
    /** If non-null, card background renders as a vertical gradient from defaultCardColor to cardGradientBottomColor. */
    val cardGradientBottomColor: Int?
    val cardBorderColor: Int?
    val cardCornerRadiusRatio: Float

    val textPrimaryColor: Int
    val textSecondaryColor: Int
    val textAccentColor: Int
    val headingColor: Int
    val valueTypeface: Typeface?
    val labelTypeface: Typeface?

    val gaugeStyle: GaugeStyle
    val gaugeTrackColor: Int
    val gaugeStrokeRatio: Float
    val gaugeTickColor: Int?
    /** If non-null, draws an illuminated circular needle pip/cap at the current value tip of the gauge arc. */
    val gaugeNeedlePipColor: Int?
    fun resolveGaugeColor(fraction: Double): Int

    val defaultGraphColor: Int
    val graphGridColor: Int?
    val graphAreaFillAlpha: Int
    /** If true, graphs render with smooth cubic Bezier curves instead of straight jagged line segments. */
    val smoothGraphCurves: Boolean
    /** If true, graph area fill renders as a vertical linear gradient fading to transparent at the bottom. */
    val graphAreaGradient: Boolean
    /** If true, draws an illuminated live telemetry beacon dot at the most recent data point. */
    val graphBeaconDot: Boolean
}

/**
 * Modern CarPlay-inspired EV telemetry theme with depth gradients, smooth curved graphs,
 * vertical gradient glows, and illuminated dials. Default theme.
 */
object EvDashboardTheme : DashboardTheme {
    override val id = DashboardThemeId.EV_DASHBOARD
    override val displayName = "EV Dashboard (Luminous)"

    override val backgroundColor: Int = colorRgb(10, 15, 26) // #0A0F1A deep midnight slate
    override val defaultCardColor: Int = colorRgb(22, 34, 56) // #162238 rich slate navy top
    override val cardGradientBottomColor: Int = colorRgb(13, 20, 34) // #0D1422 deep navy glass bottom
    override val cardBorderColor: Int = colorArgb(90, 0, 220, 255) // #5A00DCFF luminous electric cyan border
    override val cardCornerRadiusRatio: Float = 0.16f

    override val textPrimaryColor: Int = colorRgb(250, 252, 255) // #FAFCFF crisp white
    override val textSecondaryColor: Int = colorRgb(148, 163, 184) // #94A3B8 slate blue-grey
    override val textAccentColor: Int = colorRgb(0, 229, 255) // #00E5FF electric cyan
    override val headingColor: Int = colorRgb(0, 229, 255) // #00E5FF cyan headings
    override val valueTypeface: Typeface?
        get() = runCatching { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }.getOrNull()
    override val labelTypeface: Typeface?
        get() = runCatching { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }.getOrNull()

    override val gaugeStyle: GaugeStyle = GaugeStyle.SWEPT_ARC_270
    override val gaugeTrackColor: Int = colorRgb(26, 38, 57) // #1A2639 slate track
    override val gaugeStrokeRatio: Float = 0.14f
    override val gaugeTickColor: Int = colorArgb(140, 148, 163, 184)
    override val gaugeNeedlePipColor: Int = colorRgb(255, 255, 255)

    override fun resolveGaugeColor(fraction: Double): Int = when {
        fraction < 0.15 -> colorRgb(239, 68, 68) // #EF4444 red alert
        fraction < 0.30 -> colorRgb(245, 158, 11) // #F59E0B warning amber
        else -> colorRgb(74, 222, 128) // #4ADE80 vibrant electric green
    }

    override val defaultGraphColor: Int = colorRgb(0, 229, 255) // #00E5FF electric cyan
    override val graphGridColor: Int = colorArgb(35, 255, 255, 255)
    override val graphAreaFillAlpha: Int = 70
    override val smoothGraphCurves: Boolean = true
    override val graphAreaGradient: Boolean = true
    override val graphBeaconDot: Boolean = true
}

/**
 * Minimalist flat slate theme with cyan accents and swept dials.
 */
object Ioniq5CompanionDashboardTheme : DashboardTheme {
    override val id = DashboardThemeId.IONIQ5_COMPANION
    override val displayName = "IONIQ 5 Companion (Flat)"

    override val backgroundColor: Int = colorRgb(11, 17, 30) // #0B111E deep slate navy
    override val defaultCardColor: Int = colorRgb(22, 32, 51) // #162033 slate card surface
    override val cardGradientBottomColor: Int? = null
    override val cardBorderColor: Int = colorArgb(80, 0, 212, 255) // #5000D4FF cyan luminous border
    override val cardCornerRadiusRatio: Float = 0.16f

    override val textPrimaryColor: Int = colorRgb(248, 250, 252) // #F8FAFC off-white
    override val textSecondaryColor: Int = colorRgb(148, 163, 184) // #94A3B8 slate blue-grey
    override val textAccentColor: Int = colorRgb(0, 212, 255) // #00D4FF electric cyan
    override val headingColor: Int = colorRgb(0, 212, 255) // #00D4FF cyan tile headings
    override val valueTypeface: Typeface?
        get() = runCatching { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }.getOrNull()
    override val labelTypeface: Typeface?
        get() = runCatching { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }.getOrNull()

    override val gaugeStyle: GaugeStyle = GaugeStyle.SWEPT_ARC_270
    override val gaugeTrackColor: Int = colorRgb(30, 41, 59) // #1E293B slate track
    override val gaugeStrokeRatio: Float = 0.14f
    override val gaugeTickColor: Int = colorArgb(120, 148, 163, 184)
    override val gaugeNeedlePipColor: Int? = null

    override fun resolveGaugeColor(fraction: Double): Int = when {
        fraction < 0.15 -> colorRgb(239, 68, 68) // #EF4444 red alert
        fraction < 0.30 -> colorRgb(245, 158, 11) // #F59E0B warning amber
        else -> colorRgb(74, 222, 128) // #4ADE80 vibrant electric green
    }

    override val defaultGraphColor: Int = colorRgb(0, 212, 255) // #00D4FF electric cyan
    override val graphGridColor: Int = colorArgb(35, 255, 255, 255)
    override val graphAreaFillAlpha: Int = 35
    override val smoothGraphCurves: Boolean = false
    override val graphAreaGradient: Boolean = false
    override val graphBeaconDot: Boolean = false
}

/**
 * Minimalist flat dark theme with standard circular dials and zero glare.
 */
object ClassicDarkDashboardTheme : DashboardTheme {
    override val id = DashboardThemeId.CLASSIC_DARK
    override val displayName = "Classic Dark (Flat)"

    override val backgroundColor: Int = colorRgb(0, 0, 0) // Black
    override val defaultCardColor: Int = colorRgb(28, 28, 28) // #1C1C1C
    override val cardGradientBottomColor: Int? = null
    override val cardBorderColor: Int? = null
    override val cardCornerRadiusRatio: Float = 0.12f

    override val textPrimaryColor: Int = colorRgb(255, 255, 255) // White
    override val textSecondaryColor: Int = colorRgb(160, 160, 160) // #A0A0A0
    override val textAccentColor: Int = colorRgb(60, 200, 100) // #3CC864
    override val headingColor: Int = colorRgb(160, 160, 160)
    override val valueTypeface: Typeface?
        get() = runCatching { Typeface.DEFAULT_BOLD }.getOrNull()
    override val labelTypeface: Typeface?
        get() = runCatching { Typeface.DEFAULT }.getOrNull()

    override val gaugeStyle: GaugeStyle = GaugeStyle.FULL_RING
    override val gaugeTrackColor: Int = colorRgb(40, 40, 40) // #282828
    override val gaugeStrokeRatio: Float = 0.18f
    override val gaugeTickColor: Int? = null
    override val gaugeNeedlePipColor: Int? = null

    override fun resolveGaugeColor(fraction: Double): Int = when {
        fraction < 0.2 -> colorRgb(220, 60, 60)
        fraction < 0.5 -> colorRgb(230, 180, 40)
        else -> colorRgb(60, 200, 100)
    }

    override val defaultGraphColor: Int = colorRgb(200, 200, 200)
    override val graphGridColor: Int? = null
    override val graphAreaFillAlpha: Int = 0
    override val smoothGraphCurves: Boolean = false
    override val graphAreaGradient: Boolean = false
    override val graphBeaconDot: Boolean = false
}

/** Alias to ensure backwards compatibility with any references to the default theme. */
val DefaultDashboardTheme: DashboardTheme = EvDashboardTheme

object DashboardThemeRegistry {
    val allThemes: List<DashboardTheme> = listOf(
        EvDashboardTheme,
        Ioniq5CompanionDashboardTheme,
        ClassicDarkDashboardTheme,
    )

    val defaultTheme: DashboardTheme = EvDashboardTheme

    fun get(id: DashboardThemeId): DashboardTheme = when (id) {
        DashboardThemeId.EV_DASHBOARD -> EvDashboardTheme
        DashboardThemeId.IONIQ5_COMPANION -> Ioniq5CompanionDashboardTheme
        DashboardThemeId.CLASSIC_DARK -> ClassicDarkDashboardTheme
    }

    fun get(idString: String?): DashboardTheme = get(DashboardThemeId.fromString(idString))

    fun nextTheme(current: DashboardThemeId): DashboardThemeId = when (current) {
        DashboardThemeId.EV_DASHBOARD -> DashboardThemeId.IONIQ5_COMPANION
        DashboardThemeId.IONIQ5_COMPANION -> DashboardThemeId.CLASSIC_DARK
        DashboardThemeId.CLASSIC_DARK -> DashboardThemeId.EV_DASHBOARD
    }
}
