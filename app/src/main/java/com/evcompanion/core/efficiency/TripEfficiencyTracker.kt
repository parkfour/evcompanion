package com.evcompanion.core.efficiency

/**
 * Tracks trip average and smoothed instantaneous efficiency (mi/kWh) derived directly
 * from BMS high-voltage cumulative energy counters and vehicle speed.
 */
class TripEfficiencyTracker(
    private val emaAlpha: Double = 0.25,
) {
    data class EfficiencySnapshot(
        val tripAverageMiPerKwh: Double?,
        val instantMiPerKwh: Double?,
        val isRegen: Boolean,
        val tripDistanceMiles: Double?,
        val tripNetKwh: Double?,
        val tripRegenKwh: Double?,
    )

    private var startOdometerMiles: Double? = null
    private var startCumDischargedKwh: Double? = null
    private var startCumChargedKwh: Double? = null
    private var smoothedInstantMiPerKwh: Double? = null

    fun reset() {
        startOdometerMiles = null
        startCumDischargedKwh = null
        startCumChargedKwh = null
        smoothedInstantMiPerKwh = null
    }

    /**
     * Updates tracker with latest readings and returns calculated efficiency.
     *
     * @param speedMph Current vehicle speed in mph.
     * @param batteryPowerKw Net battery power in kW (positive = discharging, negative = regen).
     * @param cumDischargedKwh BMS Cumulative Energy Discharged in kWh.
     * @param cumChargedKwh BMS Cumulative Energy Charged in kWh.
     * @param odometerMiles Current odometer in miles.
     */
    fun update(
        speedMph: Double?,
        batteryPowerKw: Double?,
        cumDischargedKwh: Double?,
        cumChargedKwh: Double?,
        odometerMiles: Double?,
    ): EfficiencySnapshot {
        // Initialize baseline cumulative counters on first receipt
        if (startCumDischargedKwh == null && cumDischargedKwh != null && cumDischargedKwh > 0.0) {
            startCumDischargedKwh = cumDischargedKwh
        }
        if (startCumChargedKwh == null && cumChargedKwh != null && cumChargedKwh > 0.0) {
            startCumChargedKwh = cumChargedKwh
        }
        if (startOdometerMiles == null && odometerMiles != null && odometerMiles > 0.0) {
            startOdometerMiles = odometerMiles
        }

        // 1. Calculate trip distance & energy
        val tripDist = if (odometerMiles != null && startOdometerMiles != null) {
            (odometerMiles - startOdometerMiles!!).coerceAtLeast(0.0)
        } else null

        val deltaDischarge = if (cumDischargedKwh != null && startCumDischargedKwh != null) {
            (cumDischargedKwh - startCumDischargedKwh!!).coerceAtLeast(0.0)
        } else 0.0

        val deltaCharge = if (cumChargedKwh != null && startCumChargedKwh != null) {
            (cumChargedKwh - startCumChargedKwh!!).coerceAtLeast(0.0)
        } else 0.0

        val netTripKwh = (deltaDischarge - deltaCharge)

        // 2. Trip Average mi/kWh
        val tripAverage = if (tripDist != null && tripDist >= 0.1 && netTripKwh >= 0.05) {
            (tripDist / netTripKwh).coerceIn(0.1, 25.0)
        } else null

        // 3. Instantaneous mi/kWh
        var isRegen = false
        val instant = if (batteryPowerKw != null && speedMph != null) {
            when {
                batteryPowerKw < -0.5 -> {
                    isRegen = true
                    null
                }
                speedMph < 1.5 || batteryPowerKw <= 0.2 -> {
                    0.0
                }
                else -> {
                    val rawInstant = (speedMph / batteryPowerKw).coerceIn(0.1, 20.0)
                    val prev = smoothedInstantMiPerKwh
                    val smoothed = if (prev == null || prev == 0.0) {
                        rawInstant
                    } else {
                        (1.0 - emaAlpha) * prev + emaAlpha * rawInstant
                    }
                    smoothedInstantMiPerKwh = smoothed
                    smoothed
                }
            }
        } else null

        return EfficiencySnapshot(
            tripAverageMiPerKwh = tripAverage,
            instantMiPerKwh = instant,
            isRegen = isRegen,
            tripDistanceMiles = tripDist,
            tripNetKwh = if (startCumDischargedKwh != null) netTripKwh else null,
            tripRegenKwh = if (startCumChargedKwh != null) deltaCharge else null,
        )
    }
}
