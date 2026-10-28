package com.evcompanion.core.preset

enum class PidCategory(val label: String) {
    BATTERY("Battery"),
    CELL_VOLTAGES("Cell Voltages"),
    CHARGING("Charging"),
    CLIMATE("Climate"),
    TIRES("Tires"),
    CHASSIS("Chassis"),
    CALCULATED("Calculated"),
}

/**
 * The Torque CSV's own `NNN_` name prefixes (000_, 001_, 004_...) don't map to a clean single
 * category - 001_ alone covers climate, tires, speed and steering. Keyword matching on the PID
 * name gives a much more useful grouping for the UI than the prefix would.
 */
internal fun categorize(name: String): PidCategory {
    val lower = name.lowercase()
    return when {
        name.startsWith("!") -> PidCategory.CALCULATED
        "cell voltage" in lower -> PidCategory.CELL_VOLTAGES
        "tire" in lower -> PidCategory.TIRES
        "charge" in lower || "charging" in lower -> PidCategory.CHARGING
        "temperature" in lower && "battery" !in lower -> PidCategory.CLIMATE
        "steering" in lower || ("speed" in lower && "battery" !in lower) || "odometer" in lower ->
            PidCategory.CHASSIS
        name.startsWith("004_calc") -> PidCategory.CALCULATED
        else -> PidCategory.BATTERY
    }
}
