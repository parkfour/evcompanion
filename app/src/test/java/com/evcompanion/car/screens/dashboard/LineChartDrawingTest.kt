package com.evcompanion.car.screens.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineChartDrawingTest {

    @Test
    fun `axes go to the first three units in order, the rest get none`() {
        // ACTUAL kW, REQ kW, SOC %, MIN TEMP C, a 4th unit, a series with no data yet
        assertEquals(
            listOf(0, 0, 1, 2, null, null),
            LineChartDrawing.assignAxes(listOf("kW", "kW", "%", "C", "V", null)),
        )
    }

    @Test
    fun `nice ticks cover the data with round steps`() {
        assertEquals(listOf(0.0, 100.0, 200.0, 300.0, 400.0), LineChartDrawing.niceTicks(12.0, 302.0))
        assertEquals(listOf(24.0, 26.0, 28.0, 30.0, 32.0), LineChartDrawing.niceTicks(24.0, 32.0))
    }

    @Test
    fun `flat data still gets a usable range`() {
        val ticks = LineChartDrawing.niceTicks(40.0, 40.0)
        assertTrue(ticks.first() < 40.0 && ticks.last() > 40.0)
    }

    @Test
    fun `axis and legend labels`() {
        assertEquals("now", LineChartDrawing.formatAgo(0))
        assertEquals("-5m", LineChartDrawing.formatAgo(300))
        assertEquals("-30s", LineChartDrawing.formatAgo(30))
        assertEquals("229.9 kW", LineChartDrawing.formatValue(229.94, "kW"))
        assertEquals("301.9 A", LineChartDrawing.formatValue(301.9, "A"))
        assertEquals("28 %", LineChartDrawing.formatValue(27.5, "%"))
        assertEquals("30.0 °C", LineChartDrawing.formatValue(30.0, "C"))
        assertEquals("--", LineChartDrawing.formatValue(null, "kW"))
    }
}
