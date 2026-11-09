package com.evcompanion.core.vehicle

import com.evcompanion.core.poi.EvCharger

/**
 * The vehicle-specific intricacies beyond the PID formulas (those live in the preset CSV) and the
 * layout (dashboard JSON): which PID plays which role, sign conventions, per-ECU polling limits
 * and adapter quirks, and charging-detection thresholds. Loaded from `assets/vehicles/<presetId>.json`,
 * falling back to `vehicles/default.json` - see VehicleProfileRepository.
 *
 * Every field defaults to generic, standards-only behaviour, so a profile only has to state what
 * differs for its car.
 */
data class VehicleProfile(
    val name: String = "Generic",
    val roles: Roles = Roles(),
    /** True when the BMS reports discharge (driving) current as positive, charge as negative. */
    val packCurrentPositiveIsDischarge: Boolean = true,
    val charging: ChargingDetection = ChargingDetection(),
    val ecus: List<Ecu> = emptyList(),
    val chargerLimits: ChargerLimits = ChargerLimits(),
) {
    /**
     * What this car can actually draw from a charger, so the nearby-charger list shows real
     * speeds (a 250 kW Tesla Supercharger gives an IONIQ 5 about 96 kW) and hides sites it
     * can't use at all.
     */
    data class ChargerLimits(
        /** The car's peak DC charge rate; null = no cap. */
        val maxDcKw: Double? = null,
        /** The car's onboard AC charger rate; null = no cap. */
        val maxAcKw: Double? = null,
        val networks: List<NetworkRule> = emptyList(),
    ) {
        /** Drops excluded sites and caps each charger's power to what this car would get. */
        fun applyTo(chargers: List<EvCharger>): List<EvCharger> = chargers.mapNotNull { c ->
            val text = "${c.network.orEmpty()} ${c.name}"
            val rules = networks.filter { text.contains(it.match, ignoreCase = true) }
            if (rules.any { it.exclude }) return@mapNotNull null
            if (c.maxPowerKw <= 0) return@mapNotNull c // unknown stays unknown
            val carCap = if (c.maxPowerKw <= AC_MAX_KW) maxAcKw else maxDcKw
            val caps = listOfNotNull(carCap, rules.firstNotNullOfOrNull { it.maxKw })
            c.copy(maxPowerKw = (caps + c.maxPowerKw.toDouble()).min().toInt())
        }

        private companion object {
            /** At or below this a public charger is AC in practice (22 kW is the 3-phase AC top). */
            const val AC_MAX_KW = 22
        }
    }

    /** Matches a charger whose operator or name contains [match] (case-insensitive). */
    data class NetworkRule(val match: String, val maxKw: Double? = null, val exclude: Boolean = false)

    /**
     * PID-name candidates for each role, tried in order; each matches a preset row whose name
     * contains it (case-insensitive) - the same lookup convention the dashboard uses.
     */
    data class Roles(
        val speed: List<String> = listOf("Vehicle Speed"),
        val odometer: List<String> = listOf("Odometer"),
        val packPower: List<String> = listOf("Battery Power"),
        val packCurrent: List<String> = listOf("Battery Current"),
        val energyDischarged: List<String> = listOf("Cumulative Energy Discharged"),
        val energyCharged: List<String> = listOf("Cumulative Energy Charged"),
        /** One entry per drive motor; empty means "no motor-speed PIDs" (charging detection off). */
        val motorSpeeds: List<String> = emptyList(),
    )

    data class ChargingDetection(
        val minChargeCurrentAmps: Double = 1.5,
        val maxMotorRpm: Double = 50.0,
        val holdSeconds: Int = 30,
    )

    data class Ecu(
        val header: String,
        val name: String? = null,
        /** Minimum time between requests to this ECU; null = the engine's default. */
        val minIntervalMs: Long? = null,
        /** Per-PID overrides of [minIntervalMs], keyed by mode+PID without "0x" (e.g. "220105"). */
        val pidIntervalsMs: Map<String, Long> = emptyMap(),
        /** CAN ID to listen on (ELM327 ATCRA) when replies don't come from header+8. */
        val receiveFilter: String? = null,
    )

    private val ecusByHeader: Map<String, Ecu> = ecus.associateBy { it.header.uppercase() }

    fun ecu(header: String?): Ecu? = header?.let { ecusByHeader[it.trim().uppercase()] }

    /** Positive when energy flows into the pack, whatever this car's sign convention. */
    fun chargeCurrent(packCurrent: Double): Double =
        if (packCurrentPositiveIsDischarge) -packCurrent else packCurrent
}
