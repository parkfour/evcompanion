package com.evcompanion.core.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayObdTransportTest {

    @Test
    fun `replays the recorded response for a captured command`() = runTest {
        val log = listOf(
            "14:32:10.100 I/BleObdTransport: TX: 22E004",
            "14:32:10.200 I/BleObdTransport: RX: 62E0045A\\r\\r",
        )
        val transport = ReplayObdTransport(log)

        assertEquals("62E0045A\r\r", transport.sendRaw("22E004"))
    }

    @Test
    fun `cycles through multiple recorded responses for the same command then loops back`() = runTest {
        val log = listOf(
            "14:32:10.100 I/BleObdTransport: TX: 22E004",
            "14:32:10.200 I/BleObdTransport: RX: 62E00420",
            "14:32:10.300 I/BleObdTransport: TX: 22E004",
            "14:32:10.400 I/BleObdTransport: RX: 62E00440",
        )
        val transport = ReplayObdTransport(log)

        assertEquals("62E00420", transport.sendRaw("22E004"))
        assertEquals("62E00440", transport.sendRaw("22E004"))
        assertEquals("62E00420", transport.sendRaw("22E004"))
    }

    @Test
    fun `an AT command never seen in the log defaults to OK`() = runTest {
        val transport = ReplayObdTransport(emptyList())

        assertEquals("OK", transport.sendRaw("ATZ"))
    }

    @Test
    fun `a non-AT command never seen in the log defaults to NO DATA`() = runTest {
        val transport = ReplayObdTransport(emptyList())

        assertEquals("NO DATA", transport.sendRaw("22BC06"))
    }

    @Test
    fun `command matching is case-insensitive`() = runTest {
        val log = listOf(
            "14:32:10.100 I/BleObdTransport: TX: atsh770",
            "14:32:10.200 I/BleObdTransport: RX: OK",
        )
        val transport = ReplayObdTransport(log)

        assertEquals("OK", transport.sendRaw("ATSH770"))
    }

    @Test
    fun `connect reports Connected without touching any real transport`() = runTest {
        val transport = ReplayObdTransport(emptyList())

        transport.connect("ignored")

        assertEquals(ObdConnectionState.Connected, transport.connectionState.value)
    }
}
