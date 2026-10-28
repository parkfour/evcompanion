package com.evcompanion.core.dashboard

import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.preset.PidCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardConfigTest {

    private fun value(pidName: String, v: Double, unit: String = ""): DecodedValue =
        DecodedValue(pidName, pidName, PidCategory.BATTERY, v, unit, 0L)

    @Test
    fun `findValue matches by substring, first name wins`() {
        val values = mapOf("000_State of Charge Display" to value("000_State of Charge Display", 42.0))
        assertEquals(42.0, findValue(values, listOf("Nonexistent", "State of Charge Display"))?.value)
        assertNull(findValue(values, listOf("Nonexistent")))
    }

    @Test
    fun `TileValueDef negate flips sign for display only`() {
        val values = mapOf("Battery Power" to value("Battery Power", -50.0))
        val sub = TileValueDef(listOf("Battery Power"), negate = true)
        assertEquals(50.0, sub.resolve(values)?.value)
    }

    @Test
    fun `TileValueDef showIf hides the value unless the condition PID matches`() {
        val base = TileValueDef(
            pidNames = listOf("Battery Heater Temperature"),
            showIfPidName = "Battery Conditioning Active",
            showIfEquals = 1.0,
        )
        val conditioningOff = mapOf(
            "Battery Conditioning Active" to value("Battery Conditioning Active", 0.0),
            "Battery Heater Temperature" to value("Battery Heater Temperature", 35.0),
        )
        val conditioningOn = conditioningOff + ("Battery Conditioning Active" to value("Battery Conditioning Active", 1.0))

        assertNull(base.resolve(conditioningOff))
        assertEquals(35.0, base.resolve(conditioningOn)?.value)
    }

    @Test
    fun `ColorRules evaluates rules in order and falls back to default`() {
        val rules = ColorRules(
            rules = listOf(
                ColorRule(emptyList(), ColorRule.Compare.LT, 10.0, RED),
                ColorRule(emptyList(), ColorRule.Compare.LT, 20.0, YELLOW),
            ),
            defaultColor = GREEN,
        )
        assertEquals(RED, rules.resolve(ownValue = 5.0, values = emptyMap()))
        assertEquals(YELLOW, rules.resolve(ownValue = 15.0, values = emptyMap()))
        assertEquals(GREEN, rules.resolve(ownValue = 50.0, values = emptyMap()))
    }

    @Test
    fun `ColorRule with pidNames matches any one of several named PIDs`() {
        val rule = ColorRule(
            listOf("Tire Pressure Front Left", "Tire Pressure Front Right"),
            ColorRule.Compare.LT,
            30.0,
            WARNING,
        )
        val okValues = mapOf(
            "Tire Pressure Front Left" to value("Tire Pressure Front Left", 32.0),
            "Tire Pressure Front Right" to value("Tire Pressure Front Right", 33.0),
        )
        val lowValues = okValues + ("Tire Pressure Front Right" to value("Tire Pressure Front Right", 25.0))

        assertEquals(false, rule.matches(null, okValues))
        assertEquals(true, rule.matches(null, lowValues))
    }

    companion object {
        private const val RED = 0xDC3C3C
        private const val YELLOW = 0xE6B428
        private const val GREEN = 0x3CC864
        private const val WARNING = 0x965A14
    }
}
