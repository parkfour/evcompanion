package com.evcompanion.core.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Replays a previously captured obd_debug_*.txt log (see DebugFileLogger) instead of talking to
 * real hardware - lets the whole app (dashboard, graphs, every screen) run against a real past
 * drive at a desk, with no adapter or car needed. Each command is answered with that command's
 * recorded responses in the order they originally occurred, cycling back to the first once
 * exhausted so a continuously-polling PollingEngine never dead-ends on a finite capture. A
 * command never seen in the log falls back to "OK"/"NO DATA" like FakeObdTransport, so an init
 * sequence that drifted slightly from the captured one still succeeds.
 */
class ReplayObdTransport(logLines: List<String>) : ObdTransport {

    private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val responses: Map<String, List<String>> = parseLog(logLines)
    private val nextIndex = mutableMapOf<String, Int>()

    override suspend fun connect(deviceAddress: String) {
        _connectionState.value = ObdConnectionState.Connected
    }

    override suspend fun disconnect() {
        _connectionState.value = ObdConnectionState.Disconnected
    }

    override suspend fun sendRaw(command: String): String {
        val key = command.uppercase()
        val recorded = responses[key]
        if (recorded.isNullOrEmpty()) {
            return if (key.startsWith("AT")) "OK" else "NO DATA"
        }
        val index = nextIndex.getOrDefault(key, 0)
        nextIndex[key] = (index + 1) % recorded.size
        return recorded[index]
    }

    companion object {
        const val ADDRESS_PREFIX = "replay:"

        fun fromFile(file: File): ReplayObdTransport = ReplayObdTransport(file.readLines())

        /** Reverses the TX/RX escaping DebugFileLogger applies when writing a log line. */
        private fun String.unescapeLoggedLine(): String = replace("\\r", "\r").replace("\\n", "\n")

        private fun parseLog(lines: List<String>): Map<String, List<String>> {
            val result = mutableMapOf<String, MutableList<String>>()
            var pendingCommand: String? = null
            for (line in lines) {
                val txIdx = line.indexOf("TX: ")
                val rxIdx = line.indexOf("RX: ")
                when {
                    txIdx >= 0 -> pendingCommand = line.substring(txIdx + 4).trim().uppercase()
                    rxIdx >= 0 -> {
                        val command = pendingCommand ?: continue
                        val response = line.substring(rxIdx + 4).unescapeLoggedLine()
                        result.getOrPut(command) { mutableListOf() }.add(response)
                        pendingCommand = null
                    }
                }
            }
            return result
        }
    }
}
