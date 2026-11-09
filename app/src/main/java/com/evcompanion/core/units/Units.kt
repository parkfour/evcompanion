package com.evcompanion.core.units

import com.evcompanion.core.polling.DecodedValue

enum class UnitSystem { IMPERIAL, METRIC }

/**
 * Display-time unit conversion. Values stay in whatever unit the car (preset) reports - so dial
 * ranges and colour thresholds in the dashboard JSON keep working - and are converted only when
 * shown as text or plotted.
 */
object Units {

    private const val KM_PER_MI = 1.609344
    private const val KPA_PER_PSI = 6.894757

    /** (value, unit) in [system]; units that don't belong to either system pass through unchanged. */
    fun convert(value: Double, unit: String, system: UnitSystem): Pair<Double, String> {
        val u = unit.lowercase().replace("/", "").replace("°", "").replace(" ", "")
        return when (system) {
            UnitSystem.IMPERIAL -> when (u) {
                "km" -> value / KM_PER_MI to "mi"
                "kmh", "kph" -> value / KM_PER_MI to "mph"
                "c" -> value * 9 / 5 + 32 to "°F"
                "kpa" -> value / KPA_PER_PSI to "psi"
                "bar" -> value * 100 / KPA_PER_PSI to "psi"
                "kwh100km" -> efficiencyFlip(value) to "mi/kWh"
                else -> value to unit
            }
            UnitSystem.METRIC -> when (u) {
                "mi" -> value * KM_PER_MI to "km"
                "mph" -> value * KM_PER_MI to "km/h"
                "f" -> (value - 32) * 5 / 9 to "°C"
                "psi" -> value * KPA_PER_PSI to "kPa"
                "mikwh" -> efficiencyFlip(value) to "kWh/100km"
                else -> value to unit
            }
        }
    }

    /** mi/kWh <-> kWh/100km are reciprocal: 100 / (mi/kWh * 1.609344). */
    private fun efficiencyFlip(v: Double): Double = if (v > 0) 100 / (v * KM_PER_MI) else 0.0

    fun DecodedValue.inUnits(system: UnitSystem): DecodedValue {
        val (v, u) = convert(value, unit, system)
        return if (u == unit) this else copy(value = v, unit = u)
    }
}
