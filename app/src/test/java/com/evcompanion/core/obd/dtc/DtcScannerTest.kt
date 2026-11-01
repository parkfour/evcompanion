package com.evcompanion.core.obd.dtc

import com.evcompanion.core.obd.Elm327Driver
import com.evcompanion.core.obd.FakeObdTransport
import com.evcompanion.core.preset.PidRow
import com.evcompanion.core.preset.Preset
import com.evcompanion.core.preset.formula.Formula
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcScannerTest {

    @Test
    fun `discoverTargetHeaders extracts unique headers from preset including 7DF`() {
        val rows = listOf(
            PidRow("SOC", "SOC", "220101", "7E4", Formula.parse("a"), null, null, "%"),
            PidRow("Motor", "RPM", "220102", "7E2", Formula.parse("a"), null, null, "rpm"),
            PidRow("VCU", "VCU", "220103", "7E5", Formula.parse("a"), null, null, ""),
            PidRow("Speed", "SPD", "010D", null, Formula.parse("a"), null, null, "km/h"),
        )
        val preset = Preset("test_preset", "Test", rows)

        val headers = DtcScanner.discoverTargetHeaders(preset)
        assertEquals(listOf("7DF", "7E4", "7E2", "7E5"), headers)
    }

    @Test
    fun `scan returns clean when all modules report NO DATA`() = runBlocking {
        val transport = FakeObdTransport()
        val driver = Elm327Driver(transport)
        driver.initialize()

        val scanner = DtcScanner()
        val result = scanner.scan(driver, listOf("7DF", "7E4"))

        assertTrue(result.isClean)
        assertEquals(0, result.codeCount)
        assertEquals(listOf("7DF", "7E4"), result.scannedHeaders)
    }

    @Test
    fun `scan parses detected DTC codes on ECU`() = runBlocking {
        val transport = FakeObdTransport()
        transport.respondTo("190209", "59 02 09 0A 80 00 23")
        val driver = Elm327Driver(transport)
        driver.initialize()

        val scanner = DtcScanner()
        val result = scanner.scan(driver, listOf("7E4"))

        assertFalse(result.isClean)
        assertEquals(1, result.codeCount)
        assertEquals("P0A80", result.codes.first().code)
        assertEquals("BMS (Battery)", result.codes.first().ecuName)
    }

    @Test
    fun `clear rejects when car is moving or not parked`() = runBlocking {
        val transport = FakeObdTransport()
        val driver = Elm327Driver(transport)
        val scanner = DtcScanner()

        val result = scanner.clear(driver, listOf("7DF", "7E4"), isCarParkedOrStopped = false)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Park") == true)
    }

    @Test
    fun `clear sends mode 04 and UDS 14 when parked`() = runBlocking {
        val transport = FakeObdTransport()
        transport.respondTo("04", "44")
        transport.respondTo("14FFFFFF", "54")
        val driver = Elm327Driver(transport)
        driver.initialize()
        val scanner = DtcScanner()

        val result = scanner.clear(driver, listOf("7DF", "7E4"), isCarParkedOrStopped = true)
        assertTrue(result.success)
        assertEquals(listOf("7DF", "7E4"), result.clearedHeaders)
        assertTrue(transport.sentCommands.contains("04"))
        assertTrue(transport.sentCommands.contains("14FFFFFF"))
    }
}
