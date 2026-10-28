package com.evcompanion.core.theme

import com.evcompanion.car.screens.dashboard.GaugeDrawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardThemeTest {

    @Test
    fun default_theme_properties_match_classic_implementation() {
        val theme = DefaultDashboardTheme
        assertEquals(DashboardThemeId.DEFAULT, theme.id)
        assertEquals("Classic Dark", theme.displayName)
        assertEquals(colorRgb(0, 0, 0), theme.backgroundColor)
        assertEquals(colorRgb(28, 28, 28), theme.defaultCardColor)
        assertNull(theme.cardBorderColor)
        assertEquals(GaugeStyle.FULL_RING, theme.gaugeStyle)
        assertEquals(colorRgb(40, 40, 40), theme.gaugeTrackColor)

        // Gauge color thresholds: <0.2 red, <0.5 amber, >=0.5 green
        assertEquals(colorRgb(220, 60, 60), theme.resolveGaugeColor(0.1))
        assertEquals(colorRgb(230, 180, 40), theme.resolveGaugeColor(0.3))
        assertEquals(colorRgb(60, 200, 100), theme.resolveGaugeColor(0.8))
    }

    @Test
    fun ioniq5_companion_theme_properties_match_carplay_style() {
        val theme = Ioniq5CompanionDashboardTheme
        assertEquals(DashboardThemeId.IONIQ5_COMPANION, theme.id)
        assertEquals("IONIQ 5 Companion", theme.displayName)
        assertEquals(colorRgb(11, 17, 30), theme.backgroundColor)
        assertEquals(colorRgb(22, 32, 51), theme.defaultCardColor)
        assertNotNull(theme.cardBorderColor)
        assertEquals(GaugeStyle.SWEPT_ARC_270, theme.gaugeStyle)
        assertEquals(colorRgb(30, 41, 59), theme.gaugeTrackColor)
        assertNotNull(theme.gaugeTickColor)

        // Signature EV color thresholds: <0.15 red alert, <0.30 warning amber, >=0.30 vibrant green (#4ADE80)
        assertEquals(colorRgb(239, 68, 68), theme.resolveGaugeColor(0.10))
        assertEquals(colorRgb(245, 158, 11), theme.resolveGaugeColor(0.20))
        assertEquals(colorRgb(74, 222, 128), theme.resolveGaugeColor(0.50))
    }

    @Test
    fun theme_registry_lookup_and_cycling() {
        assertEquals(DefaultDashboardTheme, DashboardThemeRegistry.get(DashboardThemeId.DEFAULT))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get(DashboardThemeId.IONIQ5_COMPANION))

        // String lookup
        assertEquals(DefaultDashboardTheme, DashboardThemeRegistry.get("default"))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get("ioniq5_companion"))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get("IONIQ5"))
        assertEquals(DefaultDashboardTheme, DashboardThemeRegistry.get("unknown_value"))
        assertEquals(DefaultDashboardTheme, DashboardThemeRegistry.get(null as String?))

        // Cycling toggles between the themes
        assertEquals(DashboardThemeId.IONIQ5_COMPANION, DashboardThemeRegistry.nextTheme(DashboardThemeId.DEFAULT))
        assertEquals(DashboardThemeId.DEFAULT, DashboardThemeRegistry.nextTheme(DashboardThemeId.IONIQ5_COMPANION))
    }

    @Test
    fun gauge_drawing_format_value() {
        assertEquals("86.0 %", GaugeDrawing.formatValue(86.0, "%"))
        assertEquals("60.4 kW", GaugeDrawing.formatValue(60.42, "kW"))
        assertEquals("-- kW", GaugeDrawing.formatValue(null, "kW"))
    }
}
