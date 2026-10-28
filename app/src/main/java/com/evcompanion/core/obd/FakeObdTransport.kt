package com.evcompanion.core.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [ObdTransport] for unit tests and a future "simulator mode" with no hardware.
 * Responses are keyed by the exact command string sent (case-insensitive); AT commands not
 * explicitly registered default to "OK" so a driver's init sequence succeeds without needing
 * every command scripted.
 */
class FakeObdTransport : ObdTransport {

    private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val responses = mutableMapOf<String, String>()
    val sentCommands = mutableListOf<String>()
    var connectedAddress: String? = null
        private set

    fun respondTo(command: String, response: String) {
        responses[command.uppercase()] = response
    }

    override suspend fun connect(deviceAddress: String) {
        connectedAddress = deviceAddress
        _connectionState.value = ObdConnectionState.Connected
    }

    override suspend fun disconnect() {
        connectedAddress = null
        _connectionState.value = ObdConnectionState.Disconnected
    }

    override suspend fun sendRaw(command: String): String {
        sentCommands.add(command)
        val key = command.uppercase()
        responses[key]?.let { return it }
        if (key.startsWith("AT")) return "OK"
        return "NO DATA"
    }
}
