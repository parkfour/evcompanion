package com.evcompanion.car.screens.dashboard

import com.evcompanion.core.dashboard.GraphSeriesDef
import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.preset.PidCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphHistoryTrackerTest {

    private fun decoded(pidName: String, value: Double, unit: String) =
        DecodedValue(pidName, pidName, PidCategory.BATTERY, value, unit, timestampMillis = 0L)

    @Test
    fun `a series with negate records the flipped value`() {
        val tracker = GraphHistoryTracker()
        val series = listOf(GraphSeriesDef(pidNames = listOf("Battery Power"), negate = true))
        val values = mapOf("Battery Power" to decoded("Battery Power", -42.0, "kW"))

        tracker.record("overlay", series, windowSeconds = 300, values = values)

        assertEquals(listOf(42.0f), tracker.pointsFor("overlay", 0))
    }

    @Test
    fun `a series without negate records the raw value`() {
        val tracker = GraphHistoryTracker()
        val series = listOf(GraphSeriesDef(pidNames = listOf("SOC")))
        val values = mapOf("SOC" to decoded("SOC", 55.0, "%"))

        tracker.record("overlay", series, windowSeconds = 300, values = values)

        assertEquals(listOf(55.0f), tracker.pointsFor("overlay", 0))
    }
}
