package com.evcompanion.core.theme

import android.graphics.Typeface

enum class DashboardThemeId {
    DEFAULT,
    IONIQ5_COMPANION;

    companion object {
        fun fromString(value: String?): DashboardThemeId = when (value?.uppercase()) {
            "IONIQ5_COMPANION", "IONIQ_5", "IONIQ5", "EV_DASHBOARD" -> IONIQ5_COMPANION
            else -> DEFAULT
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
    fun resolveGaugeColor(fraction: Double): Int

    val defaultGraphColor: Int
    val graphGridColor: Int?
    val graphAreaFillAlpha: Int
}

object DefaultDashboardTheme : DashboardTheme {
    override val id = DashboardThemeId.DEFAULT
    override val displayName = "Classic Dark"

    override val backgroundColor: Int = colorRgb(0, 0, 0) // Black
    override val defaultCardColor: Int = colorRgb(28, 28, 28) // #1C1C1C
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

    override fun resolveGaugeColor(fraction: Double): Int = when {
        fraction < 0.2 -> colorRgb(220, 60, 60)
        fraction < 0.5 -> colorRgb(230, 180, 40)
        else -> colorRgb(60, 200, 100)
    }

    override val defaultGraphColor: Int = colorRgb(200, 200, 200)
    override val graphGridColor: Int? = null
    override val graphAreaFillAlpha: Int = 0
}

object Ioniq5CompanionDashboardTheme : DashboardTheme {
    override val id = DashboardThemeId.IONIQ5_COMPANION
    override val displayName = "IONIQ 5 Companion"

    override val backgroundColor: Int = colorRgb(11, 17, 30) // #0B111E deep slate navy
    override val defaultCardColor: Int = colorRgb(22, 32, 51) // #162033 slate card surface
    override val cardBorderColor: Int = colorArgb(80, 0, 212, 255) // #5000D4FF cyan luminous border
    override val cardCornerRadiusRatio: Float = 0.16f // Sleek squircle corner

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
    override val gaugeStrokeRatio: Float = 0.14f // Sleeker stroke
    override val gaugeTickColor: Int = colorArgb(120, 148, 163, 184)

    override fun resolveGaugeColor(fraction: Double): Int = when {
        fraction < 0.15 -> colorRgb(239, 68, 68) // #EF4444 red alert
        fraction < 0.30 -> colorRgb(245, 158, 11) // #F59E0B warning amber
        else -> colorRgb(74, 222, 128) // #4ADE80 vibrant electric green
    }

    override val defaultGraphColor: Int = colorRgb(0, 212, 255) // #00D4FF electric cyan
    override val graphGridColor: Int = colorArgb(35, 255, 255, 255)
    override val graphAreaFillAlpha: Int = 35
}

object DashboardThemeRegistry {
    val allThemes: List<DashboardTheme> = listOf(DefaultDashboardTheme, Ioniq5CompanionDashboardTheme)

    fun get(id: DashboardThemeId): DashboardTheme = when (id) {
        DashboardThemeId.DEFAULT -> DefaultDashboardTheme
        DashboardThemeId.IONIQ5_COMPANION -> Ioniq5CompanionDashboardTheme
    }

    fun get(idString: String?): DashboardTheme = get(DashboardThemeId.fromString(idString))

    fun nextTheme(current: DashboardThemeId): DashboardThemeId = when (current) {
        DashboardThemeId.DEFAULT -> DashboardThemeId.IONIQ5_COMPANION
        DashboardThemeId.IONIQ5_COMPANION -> DashboardThemeId.DEFAULT
    }
}
