package com.evcompanion.core.vehicle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a vehicle profile JSON (see `assets/vehicles/ioniq5_awd_77kwh.json`). Lenient like the
 * other config parsers: a missing or malformed field keeps its generic default and is reported in
 * [ParseResult.warnings]; only unparseable JSON fails outright.
 */
object VehicleProfileParser {

    data class ParseResult(val profile: VehicleProfile?, val warnings: List<String>)

    fun parse(jsonText: String): ParseResult {
        val warnings = mutableListOf<String>()
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return ParseResult(null, listOf("Invalid JSON: ${e.message}"))
        }
        val defaults = VehicleProfile()
        val profile = VehicleProfile(
            name = root.optString("name", defaults.name),
            roles = root.optJSONObject("roles")?.let { parseRoles(it, defaults.roles) } ?: defaults.roles,
            packCurrentPositiveIsDischarge = root.optBoolean("packCurrentPositiveIsDischarge", defaults.packCurrentPositiveIsDischarge),
            charging = root.optJSONObject("charging")?.let { c ->
                VehicleProfile.ChargingDetection(
                    minChargeCurrentAmps = c.optDouble("minChargeCurrentAmps", defaults.charging.minChargeCurrentAmps),
                    maxMotorRpm = c.optDouble("maxMotorRpm", defaults.charging.maxMotorRpm),
                    holdSeconds = c.optInt("holdSeconds", defaults.charging.holdSeconds),
                )
            } ?: defaults.charging,
            ecus = root.optJSONArray("ecus")?.let { parseEcus(it, warnings) } ?: emptyList(),
            chargerLimits = root.optJSONObject("chargerLimits")?.let { parseChargerLimits(it, warnings) } ?: defaults.chargerLimits,
        )
        return ParseResult(profile, warnings)
    }

    private fun parseChargerLimits(obj: JSONObject, warnings: MutableList<String>): VehicleProfile.ChargerLimits {
        fun optKw(o: JSONObject, key: String): Double? = if (o.has(key)) o.optDouble(key).takeIf { !it.isNaN() && it > 0 } else null
        val rules = obj.optJSONArray("networks")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val r = a.optJSONObject(i)
                val match = r?.optString("match")?.takeIf { it.isNotBlank() }
                if (r == null || match == null) {
                    warnings += "chargerLimits.networks[$i]: missing \"match\", skipped"
                    return@mapNotNull null
                }
                VehicleProfile.NetworkRule(match, optKw(r, "maxKw"), r.optBoolean("exclude", false))
            }
        } ?: emptyList()
        return VehicleProfile.ChargerLimits(optKw(obj, "maxDcKw"), optKw(obj, "maxAcKw"), rules)
    }

    private fun parseRoles(obj: JSONObject, d: VehicleProfile.Roles): VehicleProfile.Roles {
        fun names(key: String, default: List<String>): List<String> =
            obj.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: default
        return VehicleProfile.Roles(
            speed = names("speed", d.speed),
            odometer = names("odometer", d.odometer),
            packPower = names("packPower", d.packPower),
            packCurrent = names("packCurrent", d.packCurrent),
            energyDischarged = names("energyDischarged", d.energyDischarged),
            energyCharged = names("energyCharged", d.energyCharged),
            motorSpeeds = names("motorSpeeds", d.motorSpeeds),
        )
    }

    private fun parseEcus(array: JSONArray, warnings: MutableList<String>): List<VehicleProfile.Ecu> =
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i)
            val header = o?.optString("header")?.takeIf { it.isNotBlank() }
            if (o == null || header == null) {
                warnings += "ecus[$i]: missing \"header\", skipped"
                return@mapNotNull null
            }
            VehicleProfile.Ecu(
                header = header.uppercase(),
                name = o.optString("name").takeIf { it.isNotBlank() },
                minIntervalMs = if (o.has("minIntervalMs")) o.optLong("minIntervalMs") else null,
                pidIntervalsMs = o.optJSONObject("pidIntervalsMs")?.let { p ->
                    p.keys().asSequence().associate { it.removePrefix("0x").uppercase() to p.optLong(it) }
                } ?: emptyMap(),
                receiveFilter = o.optString("receiveFilter").takeIf { it.isNotBlank() },
            )
        }
}
