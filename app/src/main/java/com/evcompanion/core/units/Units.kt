package com.evcompanion.core.units

import com.evcompanion.core.polling.DecodedValue

enum class UnitSystem { IMPERIAL, METRIC }

enum class DistanceUnit(val label: String) {
    MILES("mi"),
    KILOMETERS("km");
}

enum class TemperatureUnit(val label: String) {
    FAHRENHEIT("°F"),
    CELSIUS("°C");
}

enum class PressureUnit(val label: String) {
    PSI("psi"),
    BAR("bar"),
    KPA("kPa");
}

enum class EfficiencyUnit(val label: String) {
    MI_PER_KWH("mi/kWh"),
    KM_PER_KWH("km/kWh"),
    KWH_PER_100KM("kWh/100km"),
    WH_PER_MI("Wh/mi"),
    WH_PER_KM("Wh/km");
}

data class UnitPreferences(
    val distance: DistanceUnit = DistanceUnit.MILES,
    val temperature: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
    val pressure: PressureUnit = PressureUnit.PSI,
    val efficiency: EfficiencyUnit = EfficiencyUnit.MI_PER_KWH,
) {
    val isMetric: Boolean
        get() = distance == DistanceUnit.KILOMETERS &&
                temperature == TemperatureUnit.CELSIUS &&
                pressure == PressureUnit.KPA &&
                efficiency == EfficiencyUnit.KWH_PER_100KM

    val isImperial: Boolean
        get() = distance == DistanceUnit.MILES &&
                temperature == TemperatureUnit.FAHRENHEIT &&
                pressure == PressureUnit.PSI &&
                efficiency == EfficiencyUnit.MI_PER_KWH

    val unitSystem: UnitSystem
        get() = if (isMetric) UnitSystem.METRIC else UnitSystem.IMPERIAL

    companion object {
        val IMPERIAL = UnitPreferences(
            distance = DistanceUnit.MILES,
            temperature = TemperatureUnit.FAHRENHEIT,
            pressure = PressureUnit.PSI,
            efficiency = EfficiencyUnit.MI_PER_KWH,
        )

        val METRIC = UnitPreferences(
            distance = DistanceUnit.KILOMETERS,
            temperature = TemperatureUnit.CELSIUS,
            pressure = PressureUnit.KPA,
            efficiency = EfficiencyUnit.KWH_PER_100KM,
        )

        fun fromSystem(system: UnitSystem): UnitPreferences = when (system) {
            UnitSystem.IMPERIAL -> IMPERIAL
            UnitSystem.METRIC -> METRIC
        }
    }
}

/**
 * Display-time unit conversion. Values stay in whatever unit the car (preset) reports - so dial
 * ranges and colour thresholds in the dashboard JSON keep working - and are converted only when
 * shown as text or plotted.
 */
object Units {

    const val KM_PER_MI = 1.609344
    const val KPA_PER_PSI = 6.894757
    const val KPA_PER_BAR = 100.0

    /** (value, unit) in [prefs]; units that don't belong to any configurable category pass through unchanged. */
    fun convert(value: Double, unit: String, prefs: UnitPreferences): Pair<Double, String> {
        val u = unit.lowercase().replace("/", "").replace("°", "").replace(" ", "")
        return when {
            // Distance & Speed
            u in listOf("km", "mi", "kmh", "kph", "mph") -> convertDistance(value, u, unit, prefs.distance)
            // Temperature
            u in listOf("c", "f") -> convertTemperature(value, u, unit, prefs.temperature)
            // Pressure
            u in listOf("kpa", "bar", "psi") -> convertPressure(value, u, unit, prefs.pressure)
            // Efficiency
            u in listOf("mikwh", "kmkwh", "kwh100km", "whmi", "whkm") -> convertEfficiency(value, u, unit, prefs.efficiency)
            // Anything else (e.g. V, A, kW, %, REGEN, etc.)
            else -> value to unit
        }
    }

    /** Backward-compatible overload using [UnitSystem]. */
    fun convert(value: Double, unit: String, system: UnitSystem): Pair<Double, String> =
        convert(value, unit, UnitPreferences.fromSystem(system))

    private fun convertDistance(value: Double, normalizedUnit: String, originalUnit: String, target: DistanceUnit): Pair<Double, String> {
        val isSpeed = normalizedUnit in listOf("kmh", "kph", "mph")
        if (isSpeed) {
            val mph = if (normalizedUnit in listOf("kmh", "kph")) value / KM_PER_MI else value
            return when (target) {
                DistanceUnit.MILES -> if (normalizedUnit == "mph") value to originalUnit else mph to "mph"
                DistanceUnit.KILOMETERS -> if (normalizedUnit in listOf("kmh", "kph")) value to originalUnit else (mph * KM_PER_MI) to "km/h"
            }
        } else {
            val miles = if (normalizedUnit == "km") value / KM_PER_MI else value
            return when (target) {
                DistanceUnit.MILES -> if (normalizedUnit == "mi") value to originalUnit else miles to "mi"
                DistanceUnit.KILOMETERS -> if (normalizedUnit == "km") value to originalUnit else (miles * KM_PER_MI) to "km"
            }
        }
    }

    private fun convertTemperature(value: Double, normalizedUnit: String, originalUnit: String, target: TemperatureUnit): Pair<Double, String> {
        return when (target) {
            TemperatureUnit.CELSIUS -> {
                if (normalizedUnit == "c") value to originalUnit
                else (value - 32.0) * 5.0 / 9.0 to "°C"
            }
            TemperatureUnit.FAHRENHEIT -> {
                if (normalizedUnit == "f") value to originalUnit
                else (value * 9.0 / 5.0 + 32.0) to "°F"
            }
        }
    }

    private fun convertPressure(value: Double, normalizedUnit: String, originalUnit: String, target: PressureUnit): Pair<Double, String> {
        val kpa = when (normalizedUnit) {
            "psi" -> value * KPA_PER_PSI
            "bar" -> value * KPA_PER_BAR
            else -> value
        }
        return when (target) {
            PressureUnit.KPA -> if (normalizedUnit == "kpa") value to originalUnit else kpa to "kPa"
            PressureUnit.BAR -> if (normalizedUnit == "bar") value to originalUnit else (kpa / KPA_PER_BAR) to "bar"
            PressureUnit.PSI -> if (normalizedUnit == "psi") value to originalUnit else (kpa / KPA_PER_PSI) to "psi"
        }
    }

    private fun convertEfficiency(value: Double, normalizedUnit: String, originalUnit: String, target: EfficiencyUnit): Pair<Double, String> {
        val miPerKwh = when (normalizedUnit) {
            "mikwh" -> value
            "kmkwh" -> value / KM_PER_MI
            "kwh100km" -> if (value > 0) 100.0 / (value * KM_PER_MI) else 0.0
            "whmi" -> if (value > 0) 1000.0 / value else 0.0
            "whkm" -> if (value > 0) 1000.0 / (value * KM_PER_MI) else 0.0
            else -> value
        }
        return when (target) {
            EfficiencyUnit.MI_PER_KWH -> if (normalizedUnit == "mikwh") value to originalUnit else miPerKwh to "mi/kWh"
            EfficiencyUnit.KM_PER_KWH -> if (normalizedUnit == "kmkwh") value to originalUnit else (miPerKwh * KM_PER_MI) to "km/kWh"
            EfficiencyUnit.KWH_PER_100KM -> if (normalizedUnit == "kwh100km") value to originalUnit else (if (miPerKwh > 0) 100.0 / (miPerKwh * KM_PER_MI) else 0.0) to "kWh/100km"
            EfficiencyUnit.WH_PER_MI -> if (normalizedUnit == "whmi") value to originalUnit else (if (miPerKwh > 0) 1000.0 / miPerKwh else 0.0) to "Wh/mi"
            EfficiencyUnit.WH_PER_KM -> if (normalizedUnit == "whkm") value to originalUnit else (if (miPerKwh > 0) 1000.0 / (miPerKwh * KM_PER_MI) else 0.0) to "Wh/km"
        }
    }

    fun DecodedValue.inUnits(prefs: UnitPreferences): DecodedValue {
        val (v, u) = convert(value, unit, prefs)
        return if (u == unit && v == value) this else copy(value = v, unit = u)
    }

    fun DecodedValue.inUnits(system: UnitSystem): DecodedValue =
        inUnits(UnitPreferences.fromSystem(system))
}
