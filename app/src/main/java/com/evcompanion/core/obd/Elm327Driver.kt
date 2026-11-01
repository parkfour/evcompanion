package com.evcompanion.core.obd

/**
 * Sequences ELM327 AT commands and OBD/UDS requests on top of a raw [ObdTransport].
 * Not thread-safe - callers (the polling engine) must serialize access, since a single
 * ELM327 adapter only ever processes one command at a time.
 */
class Elm327Driver(private val transport: ObdTransport) {

    var currentHeader: String? = null
        private set

    private var customCraActive = false

    /**
     * Forces the next [requestPid] call to re-send `ATSH`, even if the header hasn't changed.
     * A garbled or failed response can leave the adapter's own internal state out of sync;
     * re-establishing the header gives the stream a clean resync point.
     */
    fun resetHeaderTracking() {
        currentHeader = null
    }

    suspend fun initialize() {
        currentHeader = null
        customCraActive = false
        // 1. Reset adapter
        transport.sendRaw("ATZ")
        // 2. Turn off echo
        val e0 = transport.sendRaw("ATE0")
        // 3. Linefeeds off
        transport.sendRaw("ATL0")
        // 4. Spaces off
        transport.sendRaw("ATS0")
        // 5. Headers off
        transport.sendRaw("ATH0")
        // 6. Adaptive timing (mode 2 - more aggressive)
        transport.sendRaw("ATAT2")
        // 7. Tighter timeout (0x32 = ~200ms instead of 1024ms default)
        transport.sendRaw("ATST 32")
        // 8. CAN auto-formatting ON
        transport.sendRaw("ATCAF1")
        // 9. CAN flow control ON
        transport.sendRaw("ATCFC1")
        // 10. Protocol 6: ISO 15765-4 CAN (11 bit ID, 500 kbaud) - standard for modern EVs
        val sp6 = transport.sendRaw("ATSP6")
        if (sp6.contains("ERROR", ignoreCase = true) || sp6.contains("?", ignoreCase = true)) {
            // Fallback to auto-detect if ATSP6 is rejected by an older non-CAN vehicle
            transport.sendRaw("ATSP0")
        }
    }

    suspend fun setHeader(header: String?) {
        if (header != null && header != currentHeader) {
            val response = transport.sendRaw("ATSH $header")
            if (!response.contains("OK", ignoreCase = true)) {
                throw ObdTransportException("Failed to set header to $header: $response")
            }
            if (header.equals("770", ignoreCase = true)) {
                runCatching { transport.sendRaw("ATCRA 778") }
                customCraActive = true
            } else if (customCraActive) {
                runCatching { transport.sendRaw("ATAR") }
                customCraActive = false
            }
            currentHeader = header
        }
    }

    /**
     * Executes a raw OBD / UDS diagnostic command (e.g. "190209", "03", "14FFFFFF", "04")
     * against [header] and returns the parsed bytes without enforcing DID echo.
     */
    suspend fun executeDiagnosticCommand(command: String, header: String?): ByteArray {
        setHeader(header)
        val cmd = command.removePrefix("0x").removePrefix("0X").uppercase()
        val raw = transport.sendRaw(cmd)
        if (raw.trim().equals("OK", ignoreCase = true)) {
            return byteArrayOf()
        }
        return ObdResponseParser.parseBytes(raw, expectedCommand = null)
    }

    /**
     * Sends [modeAndPid] (e.g. "220102" or "0x220102") against the ECU at [header] (e.g. "7E4"),
     * changing the adapter's target header first only if it differs from the last request's.
     *
     * Validates that the response starts with the expected positive response SID (requested SID + 0x40)
     * and the exact echoed PID/DID, preventing stragglers or misattributed frames from corrupting
     * formula evaluations. Strips the framing bytes and returns data payload only.
     */
    suspend fun requestPid(modeAndPid: String, header: String?): ByteArray {
        setHeader(header)
        val command = modeAndPid.removePrefix("0x").removePrefix("0X").uppercase()
        val raw = transport.sendRaw(command)
        val bytes = ObdResponseParser.parseBytes(raw, expectedCommand = command)

        val prefixLen = command.length / 2
        if (bytes.size < prefixLen) {
            throw ObdTransportException("Response too short (${bytes.size} bytes) for command $command: $raw")
        }

        // Validate positive response SID: request service + 0x40 (e.g. 0x22 -> 0x62, 0x01 -> 0x41)
        val requestedService = command.substring(0, 2).toInt(16)
        val expectedSid = (requestedService + 0x40).toByte()
        if (bytes[0] != expectedSid) {
            throw ObdTransportException(
                "Mismatched SID for command $command: expected 0x${(expectedSid.toInt() and 0xFF).toString(16).uppercase()}, " +
                    "got 0x${(bytes[0].toInt() and 0xFF).toString(16).uppercase()}",
            )
        }

        // Validate echoed PID/DID bytes
        for (i in 1 until prefixLen) {
            val expectedByte = command.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            if (bytes[i] != expectedByte) {
                throw ObdTransportException(
                    "Mismatched DID echo at byte $i for command $command: expected 0x${(expectedByte.toInt() and 0xFF).toString(16).uppercase()}, " +
                        "got 0x${(bytes[i].toInt() and 0xFF).toString(16).uppercase()}",
                )
            }
        }

        return bytes.drop(prefixLen).toByteArray()
    }
}
