package com.evcompanion.core.units

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `granular distance conversions`() {
        val milesPrefs = UnitPreferences(distance = DistanceUnit.MILES)
        val kmPrefs = UnitPreferences(distance = DistanceUnit.KILOMETERS)

        assertConv(62.14 to "mph", Units.convert(100.0, "km/h", milesPrefs))
        assertConv(100.0 to "km/h", Units.convert(100.0, "km/h", kmPrefs))
        assertConv(16.09 to "km", Units.convert(10.0, "mi", kmPrefs))
        assertConv(10.0 to "mi", Units.convert(10.0, "mi", milesPrefs))
    }

    @Test
    fun `granular temperature conversions`() {
        val fPrefs = UnitPreferences(temperature = TemperatureUnit.FAHRENHEIT)
        val cPrefs = UnitPreferences(temperature = TemperatureUnit.CELSIUS)

        assertConv(77.0 to "°F", Units.convert(25.0, "C", fPrefs))
        assertConv(25.0 to "C", Units.convert(25.0, "C", cPrefs))
        assertConv(20.0 to "°C", Units.convert(68.0, "F", cPrefs))
        assertConv(68.0 to "F", Units.convert(68.0, "F", fPrefs))
    }

    @Test
    fun `granular pressure conversions between psi, bar, and kpa`() {
        val psiPrefs = UnitPreferences(pressure = PressureUnit.PSI)
        val barPrefs = UnitPreferences(pressure = PressureUnit.BAR)
        val kpaPrefs = UnitPreferences(pressure = PressureUnit.KPA)

        // 250 kPa -> 36.26 psi, 2.50 bar, 250 kPa
        assertConv(36.26 to "psi", Units.convert(250.0, "kPa", psiPrefs))
        assertConv(2.50 to "bar", Units.convert(250.0, "kPa", barPrefs))
        assertConv(250.0 to "kPa", Units.convert(250.0, "kPa", kpaPrefs))

        // 36 psi -> 2.48 bar, 248.21 kPa, 36 psi
        assertConv(2.48 to "bar", Units.convert(36.0, "psi", barPrefs))
        assertConv(248.21 to "kPa", Units.convert(36.0, "psi", kpaPrefs))
        assertConv(36.0 to "psi", Units.convert(36.0, "psi", psiPrefs))

        // 2.5 bar -> 36.26 psi, 250.0 kPa, 2.5 bar
        assertConv(36.26 to "psi", Units.convert(2.5, "bar", psiPrefs))
        assertConv(250.0 to "kPa", Units.convert(2.5, "bar", kpaPrefs))
        assertConv(2.5 to "bar", Units.convert(2.5, "bar", barPrefs))
    }

    @Test
    fun `granular efficiency conversions across all 5 metrics`() {
        // Base: 4.0 mi/kWh
        // 4.0 mi/kWh = 6.437 km/kWh = 15.534 kWh/100km = 250.0 Wh/mi = 155.34 Wh/km
        val miKwhPrefs = UnitPreferences(efficiency = EfficiencyUnit.MI_PER_KWH)
        val kmKwhPrefs = UnitPreferences(efficiency = EfficiencyUnit.KM_PER_KWH)
        val kwh100kmPrefs = UnitPreferences(efficiency = EfficiencyUnit.KWH_PER_100KM)
        val whMiPrefs = UnitPreferences(efficiency = EfficiencyUnit.WH_PER_MI)
        val whKmPrefs = UnitPreferences(efficiency = EfficiencyUnit.WH_PER_KM)

        assertConv(4.0 to "mi/kWh", Units.convert(4.0, "mi/kWh", miKwhPrefs))
        assertConv(6.44 to "km/kWh", Units.convert(4.0, "mi/kWh", kmKwhPrefs))
        assertConv(15.53 to "kWh/100km", Units.convert(4.0, "mi/kWh", kwh100kmPrefs))
        assertConv(250.0 to "Wh/mi", Units.convert(4.0, "mi/kWh", whMiPrefs))
        assertConv(155.34 to "Wh/km", Units.convert(4.0, "mi/kWh", whKmPrefs))

        // Round trip from kWh/100km (16.67 kWh/100km ~ 3.73 mi/kWh)
        assertConv(3.73 to "mi/kWh", Units.convert(16.67, "kWh/100km", miKwhPrefs))
        assertConv(6.00 to "km/kWh", Units.convert(16.67, "kWh/100km", kmKwhPrefs))
        assertConv(166.7 to "Wh/km", Units.convert(16.67, "kWh/100km", whKmPrefs))
    }

    @Test
    fun `unit preferences isMetric and isImperial helpers`() {
        assertTrue(UnitPreferences.METRIC.isMetric)
        assertFalse(UnitPreferences.METRIC.isImperial)
        assertEquals(UnitSystem.METRIC, UnitPreferences.METRIC.unitSystem)

        assertTrue(UnitPreferences.IMPERIAL.isImperial)
        assertFalse(UnitPreferences.IMPERIAL.isMetric)
        assertEquals(UnitSystem.IMPERIAL, UnitPreferences.IMPERIAL.unitSystem)

        val custom = UnitPreferences(
            distance = DistanceUnit.MILES,
            temperature = TemperatureUnit.CELSIUS,
            pressure = PressureUnit.PSI,
            efficiency = EfficiencyUnit.MI_PER_KWH,
        )
        assertFalse(custom.isMetric)
        assertFalse(custom.isImperial)
        assertEquals(UnitSystem.IMPERIAL, custom.unitSystem)
    }
}
