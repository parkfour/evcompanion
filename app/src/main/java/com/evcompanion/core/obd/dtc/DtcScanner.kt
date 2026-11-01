package com.evcompanion.core.obd.dtc

import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.obd.Elm327Driver
import com.evcompanion.core.obd.ObdResponseParser
import com.evcompanion.core.preset.Preset

/**
 * Executes vehicle-agnostic Diagnostic Trouble Code (DTC) scans and clear routines.
 *
 * Target ECU headers are dynamically derived from the active vehicle preset's own [Preset.rows]
 * (falling back to standard OBD broadcast `7DF`), ensuring full portability across different
 * vehicle presets (e.g. Ioniq 5, Ford Mustang Mach-E, Chevy Bolt, generic EVs).
 */
class DtcScanner {

    companion object {
        private const val TAG = "DtcScanner"

        private val KNOWN_ECU_NAMES = mapOf(
            "7DF" to "OBD Broadcast",
            "7E4" to "BMS (Battery)",
            "7E5" to "VCU (Vehicle Control)",
            "7E2" to "MCU (Motor/Inverter)",
            "7D1" to "ICCU (Charger/LDC)",
            "7A4" to "IEB (Brakes)",
            "7B3" to "FATC (Climate)",
            "770" to "BCM (Body Control)",
            "7A0" to "TPMS (Tires)",
            "7C6" to "IBU (Integrated Body)",
            "730" to "CLU (Cluster)",
            // Ford / Mach-E
            "7E7" to "BECM (Battery)",
            "7E0" to "PCM (Powertrain)",
            "7E6" to "SOBDMC (Front Motor)",
            "726" to "BCM (Body Control)",
            "760" to "ABS (Brakes)",
        )

        fun resolveEcuName(header: String?): String {
            if (header == null) return "Unknown"
            val h = header.uppercase().trim()
            return KNOWN_ECU_NAMES[h] ?: "ECU $h"
        }

        /**
         * Discovers target ECU header addresses from the active preset, always ensuring
         * the universal OBD-II broadcast address (`7DF`) is included.
         */
        fun discoverTargetHeaders(preset: Preset?): List<String> {
            val presetHeaders = preset?.rows.orEmpty()
                .mapNotNull { it.header?.trim()?.uppercase() }
                .filter { it.isNotBlank() && it.length in 3..4 }
                .distinct()

            return (listOf("7DF") + presetHeaders).distinct()
        }
    }

    /**
     * Executes a full diagnostic scan across [targetHeaders] using [driver].
     *
     * @param driver The active [Elm327Driver] (access must be serialized by caller).
     * @param targetHeaders The ECU headers to scan.
     * @param onProgress Callback invoked as each module is being scanned.
     */
    suspend fun scan(
        driver: Elm327Driver,
        targetHeaders: List<String>,
        onProgress: ((currentHeader: String, scannedCount: Int, totalCount: Int, ecuName: String) -> Unit)? = null,
    ): DtcScanResult {
        val results = mutableListOf<DtcCode>()
        val successfullyScanned = mutableListOf<String>()
        val total = targetHeaders.size

        for ((index, header) in targetHeaders.withIndex()) {
            val ecuName = resolveEcuName(header)
            onProgress?.invoke(header, index + 1, total, ecuName)

            if (header == "7DF") {
                // Universal SAE J1979 Mode 03 on broadcast address
                try {
                    val bytes = driver.executeDiagnosticCommand("03", "7DF")
                    val dtcs = DtcParser.parseStandardObdDtc(bytes, "7DF", ecuName)
                    results.addAll(dtcs)
                    successfullyScanned.add("7DF")
                    DebugFileLogger.i(TAG, "Scanned 7DF (Mode 03): ${dtcs.size} codes found")
                } catch (e: ObdResponseParser.ObdErrorResponseException) {
                    // "NO DATA" is the expected positive sign that no DTCs are set
                    successfullyScanned.add("7DF")
                    DebugFileLogger.i(TAG, "Scanned 7DF: Clean (${e.token})")
                } catch (e: Exception) {
                    DebugFileLogger.w(TAG, "7DF scan error: ${e.message}")
                }
                continue
            }

            // Enhanced UDS Service 0x19 (ReadDTCInformation) on module header
            try {
                // Query active and confirmed trouble codes
                val bytes = driver.executeDiagnosticCommand("190209", header)
                val dtcs = DtcParser.parseUdsDtc(bytes, header, ecuName)
                results.addAll(dtcs)
                successfullyScanned.add(header)
                DebugFileLogger.i(TAG, "Scanned $header ($ecuName): ${dtcs.size} codes found")
            } catch (e: ObdResponseParser.ObdErrorResponseException) {
                // "NO DATA" or "EMPTY" indicates no trouble codes in ECU memory
                successfullyScanned.add(header)
                DebugFileLogger.i(TAG, "Scanned $header ($ecuName): Clean (${e.token})")
            } catch (e: ObdResponseParser.ObdNegativeResponseException) {
                // NRC (e.g. ServiceNotSupported on this specific ECU) - ignore gracefully
                DebugFileLogger.i(TAG, "Header $header ($ecuName) does not support UDS 19: NRC 0x${e.nrc.toString(16)}")
            } catch (e: Exception) {
                DebugFileLogger.w(TAG, "Scan failed for header $header ($ecuName): ${e.message}")
            }
        }

        return DtcScanResult(
            codes = results.distinctBy { "${it.header}:${it.code}" },
            scannedHeaders = successfullyScanned,
        )
    }

    /**
     * Clears diagnostic trouble codes across [targetHeaders] using [driver].
     *
     * @param driver The active [Elm327Driver] (access must be serialized by caller).
     * @param targetHeaders The ECU headers to clear.
     * @param isCarParkedOrStopped Safety gate: must be true to proceed.
     */
    suspend fun clear(
        driver: Elm327Driver,
        targetHeaders: List<String>,
        isCarParkedOrStopped: Boolean,
        onProgress: ((currentHeader: String, clearedCount: Int, totalCount: Int) -> Unit)? = null,
    ): DtcClearResult {
        if (!isCarParkedOrStopped) {
            return DtcClearResult(
                success = false,
                errorMessage = "Vehicle must be in Park and stopped to clear codes",
            )
        }

        val cleared = mutableListOf<String>()
        val total = targetHeaders.size

        for ((index, header) in targetHeaders.withIndex()) {
            onProgress?.invoke(header, index + 1, total)

            if (header == "7DF") {
                // Universal SAE J1979 Mode 04 clear command
                try {
                    driver.executeDiagnosticCommand("04", "7DF")
                    cleared.add("7DF")
                    DebugFileLogger.i(TAG, "Cleared 7DF (Mode 04)")
                } catch (e: ObdResponseParser.ObdErrorResponseException) {
                    // Even if NO DATA on broadcast, broadcast clear command was transmitted
                    cleared.add("7DF")
                    DebugFileLogger.i(TAG, "Cleared 7DF (broadcast response: ${e.token})")
                } catch (e: Exception) {
                    DebugFileLogger.w(TAG, "Clear 7DF error: ${e.message}")
                }
                continue
            }

            // UDS Service 0x14 (ClearDiagnosticInformation) - 0xFFFFFF = all DTCs
            try {
                driver.executeDiagnosticCommand("14FFFFFF", header)
                cleared.add(header)
                DebugFileLogger.i(TAG, "Cleared $header (UDS 14FFFFFF)")
            } catch (e: ObdResponseParser.ObdErrorResponseException) {
                // Some ECUs return NO DATA or EMPTY when clearing succeeds
                cleared.add(header)
                DebugFileLogger.i(TAG, "Cleared $header (ECU response: ${e.token})")
            } catch (e: Exception) {
                DebugFileLogger.w(TAG, "Clear $header error: ${e.message}")
            }
        }

        return DtcClearResult(
            success = cleared.isNotEmpty(),
            clearedHeaders = cleared,
        )
    }
}
