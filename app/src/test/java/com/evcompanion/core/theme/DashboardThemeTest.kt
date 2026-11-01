package com.evcompanion.core.theme

import com.evcompanion.car.screens.dashboard.GaugeDrawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardThemeTest {

    @Test
    fun ev_dashboard_theme_properties_match_luminous_pizzazz_style() {
        val theme = EvDashboardTheme
        assertEquals(DashboardThemeId.EV_DASHBOARD, theme.id)
        assertEquals("EV Dashboard (Luminous)", theme.displayName)
        assertEquals(colorRgb(10, 15, 26), theme.backgroundColor)
        assertEquals(colorRgb(22, 34, 56), theme.defaultCardColor)
        assertEquals(colorRgb(13, 20, 34), theme.cardGradientBottomColor)
        assertNotNull(theme.cardBorderColor)
        assertEquals(GaugeStyle.SWEPT_ARC_270, theme.gaugeStyle)
        assertEquals(colorRgb(26, 38, 57), theme.gaugeTrackColor)
        assertNotNull(theme.gaugeTickColor)
        assertNotNull(theme.gaugeNeedlePipColor)

        // Pizzazz flags
        assertTrue(theme.smoothGraphCurves)
        assertTrue(theme.graphAreaGradient)
        assertTrue(theme.graphBeaconDot)

        // Gauge color thresholds: <0.15 red alert, <0.30 warning amber, >=0.30 vibrant green
        assertEquals(colorRgb(239, 68, 68), theme.resolveGaugeColor(0.10))
        assertEquals(colorRgb(245, 158, 11), theme.resolveGaugeColor(0.20))
        assertEquals(colorRgb(74, 222, 128), theme.resolveGaugeColor(0.50))
    }

    @Test
    fun ioniq5_companion_theme_properties_match_flat_style() {
        val theme = Ioniq5CompanionDashboardTheme
        assertEquals(DashboardThemeId.IONIQ5_COMPANION, theme.id)
        assertEquals("IONIQ 5 Companion (Flat)", theme.displayName)
        assertEquals(colorRgb(11, 17, 30), theme.backgroundColor)
        assertEquals(colorRgb(22, 32, 51), theme.defaultCardColor)
        assertNull(theme.cardGradientBottomColor)
        assertNotNull(theme.cardBorderColor)
        assertEquals(GaugeStyle.SWEPT_ARC_270, theme.gaugeStyle)
        assertEquals(colorRgb(30, 41, 59), theme.gaugeTrackColor)
        assertNotNull(theme.gaugeTickColor)
        assertNull(theme.gaugeNeedlePipColor)

        // Flat flags
        assertFalse(theme.smoothGraphCurves)
        assertFalse(theme.graphAreaGradient)
        assertFalse(theme.graphBeaconDot)

        assertEquals(colorRgb(239, 68, 68), theme.resolveGaugeColor(0.10))
        assertEquals(colorRgb(245, 158, 11), theme.resolveGaugeColor(0.20))
        assertEquals(colorRgb(74, 222, 128), theme.resolveGaugeColor(0.50))
    }

    @Test
    fun classic_dark_theme_properties_match_flat_style() {
        val theme = ClassicDarkDashboardTheme
        assertEquals(DashboardThemeId.CLASSIC_DARK, theme.id)
        assertEquals("Classic Dark (Flat)", theme.displayName)
        assertEquals(colorRgb(0, 0, 0), theme.backgroundColor)
        assertEquals(colorRgb(28, 28, 28), theme.defaultCardColor)
        assertNull(theme.cardGradientBottomColor)
        assertNull(theme.cardBorderColor)
        assertEquals(GaugeStyle.FULL_RING, theme.gaugeStyle)
        assertEquals(colorRgb(40, 40, 40), theme.gaugeTrackColor)
        assertNull(theme.gaugeTickColor)
        assertNull(theme.gaugeNeedlePipColor)

        // Flat flags
        assertFalse(theme.smoothGraphCurves)
        assertFalse(theme.graphAreaGradient)
        assertFalse(theme.graphBeaconDot)

        // Gauge color thresholds: <0.2 red, <0.5 amber, >=0.5 green
        assertEquals(colorRgb(220, 60, 60), theme.resolveGaugeColor(0.1))
        assertEquals(colorRgb(230, 180, 40), theme.resolveGaugeColor(0.3))
        assertEquals(colorRgb(60, 200, 100), theme.resolveGaugeColor(0.8))
    }

    @Test
    fun theme_registry_lookup_and_cycling() {
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.get(DashboardThemeId.EV_DASHBOARD))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get(DashboardThemeId.IONIQ5_COMPANION))
        assertEquals(ClassicDarkDashboardTheme, DashboardThemeRegistry.get(DashboardThemeId.CLASSIC_DARK))

        // Default resolution
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.defaultTheme)
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.get("default"))
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.get("unknown_value"))
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.get(null as String?))

        // Specific String lookup
        assertEquals(EvDashboardTheme, DashboardThemeRegistry.get("ev_dashboard"))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get("ioniq5_companion"))
        assertEquals(Ioniq5CompanionDashboardTheme, DashboardThemeRegistry.get("IONIQ5"))
        assertEquals(ClassicDarkDashboardTheme, DashboardThemeRegistry.get("classic_dark"))

        // Cycling through all 3 themes
        assertEquals(DashboardThemeId.IONIQ5_COMPANION, DashboardThemeRegistry.nextTheme(DashboardThemeId.EV_DASHBOARD))
        assertEquals(DashboardThemeId.CLASSIC_DARK, DashboardThemeRegistry.nextTheme(DashboardThemeId.IONIQ5_COMPANION))
        assertEquals(DashboardThemeId.EV_DASHBOARD, DashboardThemeRegistry.nextTheme(DashboardThemeId.CLASSIC_DARK))
    }

    @Test
    fun gauge_drawing_format_value() {
        assertEquals("86.0 %", GaugeDrawing.formatValue(86.0, "%"))
        assertEquals("60.4 kW", GaugeDrawing.formatValue(60.42, "kW"))
        assertEquals("REGEN", GaugeDrawing.formatValue(-1.0, "REGEN"))
        assertEquals("-- kW", GaugeDrawing.formatValue(null, "kW"))
    }
}
