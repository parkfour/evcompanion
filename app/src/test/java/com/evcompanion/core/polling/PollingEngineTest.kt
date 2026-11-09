package com.evcompanion.core.polling

import com.evcompanion.core.obd.FakeObdTransport
import com.evcompanion.core.obd.ObdTransport
import com.evcompanion.core.obd.ObdTransportFactory
import com.evcompanion.core.preset.PidRow
import com.evcompanion.core.preset.Preset
import com.evcompanion.core.preset.formula.Formula
import com.evcompanion.core.vehicle.VehicleProfile
import com.evcompanion.core.vehicle.VehicleProfileParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollingEngineTest {

    private fun testPreset(): Preset {
        val rows = listOf(
            PidRow("Battery Power", "Power", "0x220101", "7E4", Formula.parse("a*256+b"), -200.0, 200.0, "kW"),
            PidRow("Motor RPM Front", "RPMF", "0x221001", "7E2", Formula.parse("a*256+b"), 0.0, 10000.0, "rpm"),
            PidRow("Tire Pressure FL", "TPFL", "0x22C00B", "7A0", Formula.parse("a/5"), 0.0, 50.0, "psi"),
            PidRow("Outdoor Temp", "OutTemp", "220100", "7B3", Formula.parse("a-40"), -40.0, 80.0, "C"),
            PidRow("Headlight Status", "Headlight", "0x22BC03", "770", Formula.parse("a"), 0.0, 1.0, ""),
            PidRow("Calculated Power kW", "CalcPower", null, null, Formula.parse("val{Battery Power}*1.0"), -200.0, 200.0, "kW"),
        )
        return Preset("test", "Test Preset", rows)
    }

    private class TestTransportFactory(private val transport: ObdTransport) : ObdTransportFactory(null) {
        override fun create(deviceAddress: String): ObdTransport = transport
    }

    @Test
    fun `minimumSafeInterval protects slow body ECUs from flooding`() {
        val factory = TestTransportFactory(FakeObdTransport())
        val engine = PollingEngine(factory, TestScope())
        // The limits now live in the bundled IONIQ 5 profile - same values as the old hardcoded ones.
        val json = java.io.File("src/main/assets/vehicles/ioniq5_awd_77kwh.json").readText()
        engine.setVehicleProfile(VehicleProfileParser.parse(json).profile!!)

        assertEquals(5_000L, engine.minimumSafeInterval("7E4", "0x220105")) // BMS aux/SOH, per-PID
        assertEquals(500L, engine.minimumSafeInterval("7E9", "221234"))   // ECU not in profile: default
        assertEquals(25_000L, engine.minimumSafeInterval("7A0", "22C00B")) // TPMS
        assertEquals(30_000L, engine.minimumSafeInterval("730", "22F010")) // Cluster Odometer
        assertEquals(8_000L, engine.minimumSafeInterval("7B3", "220100"))  // Climate FATC
        assertEquals(1_500L, engine.minimumSafeInterval("770", "22BC03"))  // BCM Lights
        assertEquals(1_500L, engine.minimumSafeInterval("7C6", "22B002"))  // IBU Brake Light
        assertEquals(100L, engine.minimumSafeInterval("7E4", "220101"))    // BMS live data
        assertEquals(100L, engine.minimumSafeInterval("7E2", "221001"))    // MCU Motor RPM
    }

    @Test
    fun `polling updates values and evaluates calculated rows`() = runTest {
        val fakeTransport = FakeObdTransport()
        fakeTransport.respondTo("220101", "62 01 01 01 F4") // a=1, b=244 -> 500
        fakeTransport.respondTo("221001", "62 10 01 0B B8") // a=11, b=184 -> 3000

        val factory = TestTransportFactory(fakeTransport)
        val engine = PollingEngine(factory, backgroundScope, timeProvider = { testScheduler.currentTime })

        engine.setActivePreset(testPreset())
        engine.subscribeNames(setOf("Battery Power", "Motor RPM Front", "Calculated Power kW"))

        engine.connect("00:11:22:33:44:55")
        runCurrent()

        val values = engine.values.first { it.containsKey("Battery Power") }
        assertEquals(500.0, values["Battery Power"]?.value ?: 0.0, 0.001)
        assertEquals(500.0, values["Calculated Power kW"]?.value ?: 0.0, 0.001)
        assertEquals(3000.0, values["Motor RPM Front"]?.value ?: 0.0, 0.001)
        engine.disconnect()
    }

    @Test
    fun `subscribing only to a calculated row still queries its raw dependency`() = runTest {
        val fakeTransport = FakeObdTransport()
        fakeTransport.respondTo("220101", "62 01 01 01 F4") // a=1, b=244 -> 500

        val factory = TestTransportFactory(fakeTransport)
        val engine = PollingEngine(factory, backgroundScope, timeProvider = { testScheduler.currentTime })

        engine.setActivePreset(testPreset())
        // Only the calculated name is subscribed - "Battery Power" (its raw dependency) is not.
        engine.subscribeNames(setOf("Calculated Power kW"))

        engine.connect("00:11:22:33:44:55")
        runCurrent()

        val values = engine.values.first { it.containsKey("Calculated Power kW") }
        assertEquals(500.0, values["Calculated Power kW"]?.value ?: 0.0, 0.001)
        engine.disconnect()
    }

    @Test
    fun `instant efficiency uses Battery Power even when it is a formula row`() = runTest {
        // Same shape as the Ioniq 5 preset: power = current x voltage, not a raw PID.
        val preset = Preset(
            "eff", "Efficiency",
            listOf(
                PidRow("Battery Current", "Amps", "0x220101", "7E4", Formula.parse("a"), -500.0, 500.0, "A"),
                PidRow("Battery DC Voltage", "Volts", "0x220101", "7E4", Formula.parse("b*4"), 0.0, 1000.0, "V"),
                PidRow("Real Vehicle Speed", "Speed", "0x220100", "7B3", Formula.parse("a"), 0.0, 200.0, "mph"),
                PidRow("Battery Power", "kW", "0x220101", "7E4", Formula.parse("val{Battery Current}*val{Battery DC Voltage}/1000"), -270.0, 270.0, "kW"),
            ),
        )
        val fakeTransport = FakeObdTransport()
        fakeTransport.respondTo("220101", "62 01 01 19 64") // 25 A x 400 V = 10 kW
        fakeTransport.respondTo("220100", "62 01 00 32")    // 50 mph

        val engine = PollingEngine(TestTransportFactory(fakeTransport), backgroundScope, timeProvider = { testScheduler.currentTime })
        engine.setActivePreset(preset)
        engine.subscribeNames(setOf("Battery Power", "Real Vehicle Speed"))
        engine.connect("00:11:22:33:44:55")
        runCurrent()

        val values = engine.values.first { it.containsKey("Instant Efficiency") }
        assertEquals(5.0, values["Instant Efficiency"]?.value ?: 0.0, 0.001) // 50 mph / 10 kW
        engine.disconnect()
    }

    @Test
    fun `sticky header batching minimizes ATSH switches between cycles`() = runTest {
        val fakeTransport = FakeObdTransport()
        fakeTransport.respondTo("220101", "62 01 01 00 00")
        fakeTransport.respondTo("221001", "62 10 01 00 00")

        val factory = TestTransportFactory(fakeTransport)
        val engine = PollingEngine(factory, backgroundScope, timeProvider = { testScheduler.currentTime })

        engine.setActivePreset(testPreset())
        // Fast (100ms) ECUs come from the vehicle profile now, not hardcoded headers.
        engine.setVehicleProfile(VehicleProfile(ecus = listOf(VehicleProfile.Ecu("7E4", minIntervalMs = 100), VehicleProfile.Ecu("7E2", minIntervalMs = 100))))
        engine.subscribeNames(setOf("Battery Power", "Motor RPM Front"))

        engine.connect("00:11:22:33:44:55")
        runCurrent()

        val commandsAfterFirst = fakeTransport.sentCommands.toList()
        assertTrue(commandsAfterFirst.contains("220101"))
        assertTrue(commandsAfterFirst.contains("221001"))

        // Advance by FAST_POLL_INTERVAL_MILLIS so the next fast cycle triggers
        fakeTransport.sentCommands.clear()
        advanceTimeBy(PollingEngine.FAST_POLL_INTERVAL_MILLIS + 20)
        runCurrent()

        // In the second cycle, the first query executed matches the adapter's current header,
        // so only ONE ATSH switch is needed to flip to the second ECU.
        val atshCount = fakeTransport.sentCommands.count { it.startsWith("ATSH") }
        assertEquals(1, atshCount)
        engine.disconnect()
    }

    @Test
    fun `query failure causes error backoff without stalling working queries`() = runTest {
        val fakeTransport = FakeObdTransport()
        // 220101 fails with an error
        fakeTransport.respondTo("220101", "ERROR")
        // 221001 succeeds
        fakeTransport.respondTo("221001", "62 10 01 03 E8") // 1000 rpm

        val factory = TestTransportFactory(fakeTransport)
        val engine = PollingEngine(factory, backgroundScope, timeProvider = { testScheduler.currentTime })

        engine.setActivePreset(testPreset())
        engine.subscribeNames(setOf("Battery Power", "Motor RPM Front"))

        engine.connect("00:11:22:33:44:55")
        runCurrent()

        // Motor RPM Front should still succeed even though Battery Power failed
        val values = engine.values.first { it.containsKey("Motor RPM Front") }
        assertEquals(1000.0, values["Motor RPM Front"]?.value ?: 0.0, 0.001)
        engine.disconnect()
    }

    @Test
    fun `tpms response is stale until the sensors report after wake`() {
        fun hex(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Real 22C00B payloads from the 2026-11-09 log: cached at wake, then first fresh report.
        val stale = hex("FFFFFF80D34D010000D44E010000D34E010000D44E010000FF93B893B893B893B8")
        val fresh = hex("FFFFFF80C642010000C641010000C242010000C3420100004193B893B893B893B8")
        assertTrue(PollingEngine.isStaleTpms("22C00B", stale))
        assertTrue(PollingEngine.isStaleTpms("0x22C00B", stale))
        assertEquals(false, PollingEngine.isStaleTpms("22C00B", fresh))
        assertEquals(false, PollingEngine.isStaleTpms("220101", stale))
    }
}
