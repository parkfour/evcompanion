package com.evcompanion.core.obd

/**
 * Turns the raw text an ELM327 adapter sends back (with the '>' prompt already stripped by
 * the transport) into the ordered response bytes.
 *
 * Headers are kept OFF (the ELM327 power-on default; this driver never sends ATH1), and CAN
 * auto-formatting/multi-frame reassembly stays ON (ATCAF1, also the default), so what comes
 * back is just the UDS payload as a flat hex string - e.g. for a mode 22 request the first
 * byte ('a') is the 0x62 positive-response SID, the next bytes echo the requested DID, and
 * actual data follows. This class does not try to interpret or strip any of that: it hands
 * back the bytes exactly as received, in order. Stripping the response SID + PID/DID echo so
 * the remaining bytes are plain *data* (which is what the bundled preset's Torque formulas
 * index against) happens one layer up, in Elm327Driver.requestPid.
 *
 * One thing it does have to strip: some adapters (confirmed live on a Vgate iCar Pro BLE)
 * display a multi-frame CAN response as a leading byte-count header followed by each frame's
 * payload prefixed with its ISO-TP sequence number, e.g. `02E0:620105FFFB741:0F012C01012C1A...`
 * - packed back-to-back with no space or line break at all between frames, so this can't be
 * handled by splitting on whitespace the way a single-frame response can. See [parseBytes].
 */
object ObdResponseParser {

    private val ERROR_TOKENS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS INIT", "BUFFER FULL",
        "STOPPED", "ERROR", "?", "FB ERROR", "DATA ERROR", "ERR"
    )

    class ObdErrorResponseException(val token: String) :
        Exception("Adapter returned an error response: $token")

    class ObdNegativeResponseException(val service: Int, val nrc: Int) :
        Exception("UDS Negative Response: service 0x${service.toString(16).uppercase()} NRC 0x${nrc.toString(16).uppercase()}")

    // A single hex digit (the ISO-TP consecutive-frame sequence number, 0-F) immediately
    // followed by ':' - marker of a frame boundary in ELM327 formatted multi-frame mode.
    private val FRAME_MARKER = Regex("(?=[0-9A-Fa-f]:)")
    private val SEARCHING_NOTICE = Regex("(?i)SEARCHING\\.*|BUS INIT:?\\.*")

    fun parseBytes(rawResponse: String, expectedCommand: String? = null): ByteArray {
        val lines = rawResponse
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (line in lines) {
            val upper = line.uppercase()
            ERROR_TOKENS.firstOrNull { upper == it || upper.startsWith(it) }?.let {
                throw ObdErrorResponseException(line)
            }
        }

        var cleaned = rawResponse
            .replace(SEARCHING_NOTICE, "")
            .replace(Regex("[\\r\\n ]"), "")

        // Strip command echo if the adapter echoed the transmitted command at the front of the response
        if (expectedCommand != null && expectedCommand.isNotEmpty()) {
            val normCmd = expectedCommand.removePrefix("0x").removePrefix("0X").uppercase()
            if (cleaned.startsWith(normCmd, ignoreCase = true) && cleaned.length > normCmd.length) {
                val afterEcho = cleaned.substring(normCmd.length)
                // Ensure what follows looks like a valid response (SID, frame marker, or NRC)
                if (afterEcho.startsWith("62", ignoreCase = true) ||
                    afterEcho.startsWith("41", ignoreCase = true) ||
                    afterEcho.startsWith("7F", ignoreCase = true) ||
                    FRAME_MARKER.containsMatchIn(afterEcho)
                ) {
                    cleaned = afterEcho
                }
            }
        }

        val hex = if (FRAME_MARKER.containsMatchIn(cleaned)) {
            val parts = cleaned.split(FRAME_MARKER)
            // parts[0] is either empty or holds a byte-count header (e.g. "02E" = 46 bytes)
            val declaredLengthHex = parts[0].ifEmpty { null }
            val declaredByteCount = declaredLengthHex?.toIntOrNull(16)
            val assembled = parts.drop(1).joinToString("") { part ->
                if (part.length >= 2 && part[1] == ':') part.substring(2) else part
            }
            // Truncate trailing CAN frame padding (e.g. fixed 8-byte frame padding 'AAAA')
            // if a valid byte-count header was declared.
            if (declaredByteCount != null && declaredByteCount > 0 && assembled.length >= declaredByteCount * 2) {
                assembled.substring(0, declaredByteCount * 2)
            } else {
                assembled
            }
        } else {
            cleaned
        }

        if (hex.isEmpty()) throw ObdErrorResponseException("EMPTY")
        require(hex.length % 2 == 0) { "Malformed OBD response (odd hex length): $rawResponse" }

        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        // Check for UDS Negative Response (0x7F <Service> <NRC>)
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0x7F) {
            val rejectedService = bytes[1].toInt() and 0xFF
            val nrc = bytes[2].toInt() and 0xFF
            throw ObdNegativeResponseException(rejectedService, nrc)
        }

        return bytes
    }
}
