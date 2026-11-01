package com.evcompanion.core.obd.dtc

/**
 * Decodes raw OBD-II (SAE J1979) and UDS (ISO 14229) diagnostic trouble code response bytes
 * into standard alphanumeric DTC codes (Pxxxx, Cxxxx, Bxxxx, Uxxxx).
 */
object DtcParser {

    /**
     * Parses bytes from a UDS Service 0x19 (ReadDTCInformation) sub-function 0x02 response.
     * Expected positive response payload:
     *   [0x59] [0x02] [DTCStatusAvailabilityMask] [DTC_H] [DTC_M] [DTC_L] [Status] ...
     *
     * Each DTC entry is 4 bytes:
     * - 2 bytes standard DTC number (decoded via [formatDtcBytes])
     * - 1 byte failure type / subtype
     * - 1 byte status mask (bit 0 = testFailed, bit 3 = confirmedDTC, etc.)
     */
    fun parseUdsDtc(bytes: ByteArray, header: String?, ecuName: String? = null): List<DtcCode> {
        if (bytes.size < 3) return emptyList()
        val sid = bytes[0].toInt() and 0xFF
        val subFunc = bytes[1].toInt() and 0xFF
        if (sid != 0x59 || subFunc != 0x02) return emptyList()

        val results = mutableListOf<DtcCode>()
        var offset = 3
        while (offset + 3 < bytes.size) {
            val byte1 = bytes[offset].toInt() and 0xFF
            val byte2 = bytes[offset + 1].toInt() and 0xFF
            val failureType = bytes[offset + 2].toInt() and 0xFF
            val status = bytes[offset + 3].toInt() and 0xFF

            val dtcCode = formatDtcBytes(byte1, byte2)
            if (dtcCode != "P0000") {
                results.add(
                    DtcCode(
                        code = dtcCode,
                        header = header,
                        ecuName = ecuName,
                        statusByte = status,
                        failureTypeByte = failureType,
                    ),
                )
            }
            offset += 4
        }
        return results
    }

    /**
     * Parses bytes from a standard OBD-II Mode 03 (Stored DTCs) or Mode 07 (Pending DTCs) response.
     * Expected positive response payload:
     *   [0x43 or 0x47] [optional Count byte] [DTC1_H] [DTC1_L] [DTC2_H] [DTC2_L] ...
     */
    fun parseStandardObdDtc(bytes: ByteArray, header: String? = "7DF", ecuName: String? = "OBD"): List<DtcCode> {
        if (bytes.isEmpty()) return emptyList()
        val sid = bytes[0].toInt() and 0xFF
        if (sid != 0x43 && sid != 0x47) return emptyList()

        val results = mutableListOf<DtcCode>()
        // If (bytes.size - 1) is odd, offset 1 is the DTC count byte. If even, DTCs start at offset 1.
        val startOffset = if ((bytes.size - 1) % 2 != 0) 2 else 1
        var offset = startOffset
        while (offset + 1 < bytes.size) {
            val byte1 = bytes[offset].toInt() and 0xFF
            val byte2 = bytes[offset + 1].toInt() and 0xFF
            if (byte1 != 0 || byte2 != 0) {
                val dtcCode = formatDtcBytes(byte1, byte2)
                if (dtcCode != "P0000") {
                    results.add(
                        DtcCode(
                            code = dtcCode,
                            header = header,
                            ecuName = ecuName,
                        ),
                    )
                }
            }
            offset += 2
        }
        return results
    }

    /**
     * Formats two raw DTC bytes into a standard 5-character SAE J2012 / ISO 15031-6 string:
     * - High 2 bits: 00 -> 'P', 01 -> 'C', 10 -> 'B', 11 -> 'U'
     * - Next 2 bits: 0..3
     * - Next 4 bits: hex digit
     * - Low 8 bits: two hex digits
     *
     * Example: 0x01, 0x23 -> "P0123"
     * Example: 0xC1, 0x00 -> "U0100"
     * Example: 0x82, 0x34 -> "B0234"
     * Example: 0x55, 0x67 -> "C1567"
     */
    fun formatDtcBytes(byte1: Int, byte2: Int): String {
        val categoryPrefix = when ((byte1 and 0xC0) ushr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            3 -> 'U'
            else -> 'P'
        }
        val digit2 = (byte1 and 0x30) ushr 4
        val digit3 = byte1 and 0x0F
        val digit4 = (byte2 and 0xF0) ushr 4
        val digit5 = byte2 and 0x0F
        return "%c%X%X%X%X".format(categoryPrefix, digit2, digit3, digit4, digit5)
    }
}
