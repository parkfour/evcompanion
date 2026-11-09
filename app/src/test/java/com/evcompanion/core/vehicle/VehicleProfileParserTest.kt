package com.evcompanion.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VehicleProfileParserTest {

    private fun asset(name: String) = File("src/main/assets/vehicles/$name").readText()

    @Test
    fun `bundled IONIQ 5 profile parses with no warnings and carries the car's quirks`() {
        val result = VehicleProfileParser.parse(asset("ioniq5_awd_77kwh.json"))
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        val p = result.profile!!
        assertEquals(listOf("Drive Motor Speed Front", "Drive Motor Speed Rear"), p.roles.motorSpeeds)
        assertEquals("778", p.ecu("770")?.receiveFilter)
        assertEquals("BMS (Battery)", p.ecu("7e4")?.name)
        assertEquals(5_000L, p.ecu("7E4")?.pidIntervalsMs?.get("220105"))
        assertEquals(302.0, p.chargeCurrent(-302.0), 0.0) // negative BMS current = charging
    }

    @Test
    fun `default profile keeps generic behaviour - no charge detection, no ECU limits`() {
        val p = VehicleProfileParser.parse(asset("default.json")).profile!!
        assertTrue(p.roles.motorSpeeds.isEmpty())
        assertNull(p.ecu("7E4"))
        assertEquals(listOf("Battery Power"), p.roles.packPower)
    }

    @Test
    fun `bad entries are skipped with a warning, bad JSON fails`() {
        val result = VehicleProfileParser.parse("""{ "ecus": [ { "name": "no header" }, { "header": "7e4" } ] }""")
        assertEquals(1, result.profile!!.ecus.size)
        assertEquals("7E4", result.profile!!.ecus[0].header)
        assertEquals(1, result.warnings.size)
        assertNull(VehicleProfileParser.parse("not json").profile)
    }

    @Test
    fun `IONIQ 5 charger limits hide Tesla-only sites and cap power to what the car draws`() {
        val limits = VehicleProfileParser.parse(asset("ioniq5_awd_77kwh.json")).profile!!.chargerLimits
        fun charger(id: String, kw: Int, network: String?) =
            com.evcompanion.core.poi.EvCharger(id, "Site $id", "", 30.0, -85.0, maxPowerKw = kw, network = network)

        val shown = limits.applyTo(
            listOf(
                charger("supercharger-open", 250, "Tesla (Including non-tesla)"),
                charger("supercharger-closed", 250, "Tesla (Tesla-only charging)"),
                charger("ea", 350, "Electrify America"),
                charger("evgo", 50, "EVgo"),
                charger("level2", 19, "ChargePoint"),
                charger("unknown", 0, null),
            ),
        ).associate { it.id to it.maxPowerKw }

        assertEquals(
            mapOf("supercharger-open" to 96, "ea" to 235, "evgo" to 50, "level2" to 10, "unknown" to 0),
            shown,
        )
    }

    @Test
    fun `no charger limits leave chargers untouched`() {
        val c = com.evcompanion.core.poi.EvCharger("1", "x", "", 0.0, 0.0, maxPowerKw = 250, network = "Tesla")
        assertEquals(listOf(c), VehicleProfile().chargerLimits.applyTo(listOf(c)))
    }

    @Test
    fun `opposite sign convention flips charge current`() {
        val p = VehicleProfile(packCurrentPositiveIsDischarge = false)
        assertEquals(50.0, p.chargeCurrent(50.0), 0.0)
    }
}
