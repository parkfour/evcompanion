package com.evcompanion.core.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun `parses a simple hex response with spaces`() {
        val bytes = ObdResponseParser.parseBytes("62 01 05 2A 4C")
        assertArrayEquals(byteArrayOf(0x62, 0x01, 0x05, 0x2A, 0x4C), bytes)
    }

    @Test
    fun `parses a response with no spaces (ATS0)`() {
        val bytes = ObdResponseParser.parseBytes("6201052A4C")
        assertArrayEquals(byteArrayOf(0x62, 0x01, 0x05, 0x2A, 0x4C), bytes)
    }

    @Test
    fun `joins multiple lines into one byte sequence`() {
        val bytes = ObdResponseParser.parseBytes("62 01 05\r2A 4C")
        assertArrayEquals(byteArrayOf(0x62, 0x01, 0x05, 0x2A, 0x4C), bytes)
    }

    @Test
    fun `throws on NO DATA`() {
        assertThrows(ObdResponseParser.ObdErrorResponseException::class.java) {
            ObdResponseParser.parseBytes("NO DATA")
        }
    }

    @Test
    fun `throws on unable to connect`() {
        assertThrows(ObdResponseParser.ObdErrorResponseException::class.java) {
            ObdResponseParser.parseBytes("UNABLE TO CONNECT")
        }
    }

    @Test
    fun `throws on unrecognized command marker`() {
        assertThrows(ObdResponseParser.ObdErrorResponseException::class.java) {
            ObdResponseParser.parseBytes("?")
        }
    }

    @Test
    fun `strips multi-frame byte-count header and sequence markers packed with no separators`() {
        val bytes = ObdResponseParser.parseBytes("01A0:1122334455661:778899")
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte()),
            bytes,
        )
    }

    @Test
    fun `strips a SEARCHING notice ahead of a multi-frame response`() {
        val bytes = ObdResponseParser.parseBytes("SEARCHING...0050:AABBCCDDEE")
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte()), bytes)
    }

    @Test
    fun `real multi-frame capture from an IONIQ 5 decodes to the expected SID and DID echo and truncates padding`() {
        // Captured live: a mode 22 request for PID 0105 against header 7E4.
        // Declared byte count is 0x02E = 46 bytes.
        // The raw string has 48 bytes due to trailing CAN frame padding "AAAA".
        val bytes = ObdResponseParser.parseBytes(
            "SEARCHING...02E0:620105FFFB741:0F012C01012C1A2:1B1B1B1B1B1A6C" +
                "3:34616200004B1D4:0003E8272AEB005:410000000000006:001B1B1C1BAAAA",
        )
        assertEquals(46, bytes.size)
        assertArrayEquals(byteArrayOf(0x62, 0x01, 0x05), bytes.copyOfRange(0, 3))
    }

    @Test
    fun `strips command echo when expectedCommand is specified`() {
        val raw = "220101\r62 01 01 AA BB CC"
        val bytes = ObdResponseParser.parseBytes(raw, expectedCommand = "220101")
        assertArrayEquals(byteArrayOf(0x62, 0x01, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), bytes)
    }

    @Test
    fun `throws ObdNegativeResponseException on UDS negative response code`() {
        val exception = assertThrows(ObdResponseParser.ObdNegativeResponseException::class.java) {
            ObdResponseParser.parseBytes("7F 22 31")
        }
        assertEquals(0x22, exception.service)
        assertEquals(0x31, exception.nrc)
    }
}
