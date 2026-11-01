package com.evcompanion.core.obd.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcParserTest {

    @Test
    fun `formatDtcBytes decodes standard categories correctly`() {
        assertEquals("P0123", DtcParser.formatDtcBytes(0x01, 0x23))
        assertEquals("P1A00", DtcParser.formatDtcBytes(0x1A, 0x00))
        assertEquals("C1567", DtcParser.formatDtcBytes(0x55, 0x67))
        assertEquals("B0234", DtcParser.formatDtcBytes(0x82, 0x34))
        assertEquals("U0100", DtcParser.formatDtcBytes(0xC1, 0x00))
        assertEquals("U1000", DtcParser.formatDtcBytes(0xD0, 0x00))
    }

    @Test
    fun `parseUdsDtc decodes single and multiple UDS trouble codes`() {
        // Payload: [SID 59] [SubFunc 02] [AvailMask 09]
        // DTC 1: P1A00-16 with status 0x08
        // DTC 2: U0100-00 with status 0x09
        val payload = byteArrayOf(
            0x59.toByte(), 0x02.toByte(), 0x09.toByte(),
            // DTC 1: 1A 00 16 08 -> P1A00
            0x1A.toByte(), 0x00.toByte(), 0x16.toByte(), 0x08.toByte(),
            // DTC 2: C1 00 00 09 -> U0100
            0xC1.toByte(), 0x00.toByte(), 0x00.toByte(), 0x09.toByte(),
        )

        val codes = DtcParser.parseUdsDtc(payload, header = "7E4", ecuName = "BMS")
        assertEquals(2, codes.size)

        assertEquals("P1A00", codes[0].code)
        assertEquals("7E4", codes[0].header)
        assertEquals("BMS", codes[0].ecuName)
        assertEquals(0x16, codes[0].failureTypeByte)
        assertEquals(0x08, codes[0].statusByte)

        assertEquals("U0100", codes[1].code)
        assertEquals("7E4", codes[1].header)
        assertEquals("BMS", codes[1].ecuName)
        assertEquals(0x00, codes[1].failureTypeByte)
        assertEquals(0x09, codes[1].statusByte)
    }

    @Test
    fun `parseUdsDtc filters out P0000 padding`() {
        val payload = byteArrayOf(
            0x59.toByte(), 0x02.toByte(), 0x09.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        )
        val codes = DtcParser.parseUdsDtc(payload, header = "7E5", ecuName = "VCU")
        assertTrue(codes.isEmpty())
    }

    @Test
    fun `parseStandardObdDtc decodes Mode 03 response with count byte`() {
        // 43 [count: 2] [P0123: 01 23] [U0100: C1 00]
        val payload = byteArrayOf(
            0x43.toByte(), 0x02.toByte(),
            0x01.toByte(), 0x23.toByte(),
            0xC1.toByte(), 0x00.toByte(),
        )

        val codes = DtcParser.parseStandardObdDtc(payload, header = "7DF", ecuName = "OBD")
        assertEquals(2, codes.size)
        assertEquals("P0123", codes[0].code)
        assertEquals("U0100", codes[1].code)
    }
}
