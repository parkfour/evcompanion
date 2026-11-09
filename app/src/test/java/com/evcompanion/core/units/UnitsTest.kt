package com.evcompanion.core.units

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    private fun imp(v: Double, u: String) = Units.convert(v, u, UnitSystem.IMPERIAL)
    private fun met(v: Double, u: String) = Units.convert(v, u, UnitSystem.METRIC)

    private fun assertConv(expected: Pair<Double, String>, actual: Pair<Double, String>) {
        assertEquals(expected.second, actual.second)
        assertEquals(expected.first, actual.first, 0.01)
    }

    @Test
    fun `car units convert to imperial`() {
        assertConv(62.14 to "mph", imp(100.0, "kmh"))
        assertConv(62.14 to "mph", imp(100.0, "km/h"))
        assertConv(6.21 to "mi", imp(10.0, "km"))
        assertConv(77.0 to "°F", imp(25.0, "C"))
        assertConv(32.0 to "°F", imp(0.0, "°C"))
        assertConv(36.26 to "psi", imp(250.0, "kPa"))
        assertConv(3.73 to "mi/kWh", imp(16.67, "kWh/100km"))
    }

    @Test
    fun `app units convert to metric`() {
        assertConv(16.09 to "km", met(10.0, "mi"))
        assertConv(96.56 to "km/h", met(60.0, "mph"))
        assertConv(248.21 to "kPa", met(36.0, "psi"))
        assertConv(25.0 to "°C", met(77.0, "F"))
        assertConv(15.53 to "kWh/100km", met(4.0, "mi/kWh"))
    }

    @Test
    fun `units already in the target system, or unitless, pass through`() {
        assertConv(36.0 to "psi", imp(36.0, "psi"))
        assertConv(25.0 to "C", met(25.0, "C"))
        assertConv(697.2 to "V", imp(697.2, "V"))
        assertConv(-1.0 to "REGEN", met(-1.0, "REGEN"))
        assertConv(0.0 to "kWh/100km", met(0.0, "mi/kWh")) // stopped: no divide-by-zero
    }
}
