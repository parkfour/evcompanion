package com.evcompanion.core.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327DriverTest {

    @Test
    fun `initialize sends the enhanced fast AT init sequence`() = runTest {
        val transport = FakeObdTransport()
        val driver = Elm327Driver(transport)

        driver.initialize()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATST 32", "ATCAF1", "ATCFC1", "ATSP6"),
            transport.sentCommands,
        )
    }

    @Test
    fun `initialize falls back to ATSP0 if ATSP6 is rejected`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo("ATSP6", "?")
        val driver = Elm327Driver(transport)

        driver.initialize()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATST 32", "ATCAF1", "ATCFC1", "ATSP6", "ATSP0"),
            transport.sentCommands,
        )
    }

    @Test
    fun `requestPid sets header only when it changes`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo("220102", "62 01 02 AA BB")
        transport.respondTo("220103", "62 01 03 AA BB")
        transport.respondTo("220100", "62 01 00 AA BB")
        val driver = Elm327Driver(transport)

        driver.requestPid("0x220102", "7E4")
        driver.requestPid("0x220103", "7E4")
        driver.requestPid("0x220100", "7B3")

        assertEquals(
            listOf("ATSH 7E4", "220102", "220103", "ATSH 7B3", "220100"),
            transport.sentCommands,
        )
    }

    @Test
    fun `requestPid configures custom CRA for header 770 and restores ATAR when leaving 770`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo("22BC03", "62 BC 03 00 00 00 00")
        transport.respondTo("220102", "62 01 02 AA BB")
        val driver = Elm327Driver(transport)

        driver.requestPid("0x22BC03", "770")
        driver.requestPid("0x220102", "7E4")

        assertEquals(
            listOf("ATSH 770", "ATCRA 778", "22BC03", "ATSH 7E4", "ATAR", "220102"),
            transport.sentCommands,
        )
    }

    @Test
    fun `requestPid throws instead of silently continuing when a header switch is not acknowledged`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo("ATSH 7E4", "?")
        transport.respondTo("220102", "62 01 02 AA BB")
        val driver = Elm327Driver(transport)

        val error = runCatching { driver.requestPid("0x220102", "7E4") }.exceptionOrNull()

        assertTrue(error is ObdTransportException)
    }

    @Test
    fun `requestPid returns decoded response bytes with the response framing stripped`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo("220102", "62 01 02 AA BB")
        val driver = Elm327Driver(transport)

        val bytes = driver.requestPid("0x220102", "7E4")

        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), bytes)
    }

    @Test
    fun `requestPid throws on mismatched SID in response`() = runTest {
        val transport = FakeObdTransport()
        // Response contains SID 0x61 instead of positive response SID 0x62 (to request 0x22)
        transport.respondTo("220102", "61 01 02 AA BB")
        val driver = Elm327Driver(transport)

        val error = runCatching { driver.requestPid("0x220102", "7E4") }.exceptionOrNull()
        assertTrue("Expected ObdTransportException, got $error", error is ObdTransportException)
    }

    @Test
    fun `requestPid throws on mismatched DID echo in response`() = runTest {
        val transport = FakeObdTransport()
        // Response echoes PID 0105 when 0102 was requested (straggler from previous PID)
        transport.respondTo("220102", "62 01 05 AA BB")
        val driver = Elm327Driver(transport)

        val error = runCatching { driver.requestPid("0x220102", "7E4") }.exceptionOrNull()
        assertTrue("Expected ObdTransportException, got $error", error is ObdTransportException)
    }

    @Test
    fun `requestPid throws on UDS negative response code`() = runTest {
        val transport = FakeObdTransport()
        // UDS NRC: Service 0x22 rejected with conditionNotCorrect (0x22)
        transport.respondTo("220102", "7F 22 22")
        val driver = Elm327Driver(transport)

        val error = runCatching { driver.requestPid("0x220102", "7E4") }.exceptionOrNull()
        assertTrue("Expected ObdNegativeResponseException, got $error", error is ObdResponseParser.ObdNegativeResponseException)
    }

    @Test
    fun `requestPid strips framing from a real packed multi-frame capture`() = runTest {
        val transport = FakeObdTransport()
        transport.respondTo(
            "220105",
            "SEARCHING...02E0:620105FFFB741:0F012C01012C1A2:1B1B1B1B1B1A6C3:34616200004B1D" +
                "4:0003E8272AEB005:410000000000006:001B1B1C1BAAAA",
        )
        val driver = Elm327Driver(transport)

        val bytes = driver.requestPid("220105", "7E4")

        // First data byte is the byte after 62 01 05; SOC display sits at data offset 31.
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFB.toByte()), bytes.copyOfRange(0, 2))
        assertEquals(0x41, bytes[31].toInt() and 0xFF) // af/2 -> SOC display 32.5%
    }
}
