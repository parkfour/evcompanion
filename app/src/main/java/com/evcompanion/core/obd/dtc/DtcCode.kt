package com.evcompanion.core.obd.dtc

/**
 * Represents a single Diagnostic Trouble Code (DTC) detected on an ECU.
 *
 * @param code Standard 5-character alphanumeric DTC string, e.g. "P1A00", "U0100", "B1234", "C1500".
 * @param header The CAN header address where the ECU responded (e.g. "7E4", "7E5", "7DF").
 * @param ecuName Friendly ECU name, e.g. "BMS", "VCU", "ICCU", "OBD Broadcast".
 * @param statusByte UDS ISO 14229 status byte (bit 0 = testFailed, bit 3 = confirmedDTC, etc.), if available.
 * @param failureTypeByte UDS failure type byte (e.g. 0x16 = circuit low, 0x17 = circuit high), if available.
 * @param description Optional human-readable fault explanation.
 */
data class DtcCode(
    val code: String,
    val header: String?,
    val ecuName: String? = null,
    val statusByte: Int? = null,
    val failureTypeByte: Int? = null,
    val description: String? = null,
)

/**
 * Result of a diagnostic scan across target vehicle modules.
 */
data class DtcScanResult(
    val codes: List<DtcCode> = emptyList(),
    val scannedHeaders: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    val isClean: Boolean get() = codes.isEmpty() && scannedHeaders.isNotEmpty()
    val codeCount: Int get() = codes.size
}

/**
 * Result of an attempt to clear DTCs across target vehicle modules.
 */
data class DtcClearResult(
    val success: Boolean,
    val clearedHeaders: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * State machine for the active diagnostic routine.
 */
sealed class DtcScanState {
    object Idle : DtcScanState()
    data class Scanning(val currentHeader: String?, val progress: Float, val currentEcuName: String?) : DtcScanState()
    data class Completed(val result: DtcScanResult) : DtcScanState()
    data class Clearing(val progress: Float) : DtcScanState()
    data class Error(val message: String) : DtcScanState()
}
