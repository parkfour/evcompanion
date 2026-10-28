package com.evcompanion.core.obd

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-agnostic connection to an ELM327-compatible OBD-II adapter.
 * A single connection to an adapter is expected to be used from one coroutine at a time -
 * [Elm327Driver] serializes requests since ELM327 adapters process one command at a time.
 */
interface ObdTransport {

    val connectionState: StateFlow<ObdConnectionState>

    /** [deviceAddress] is transport-specific: a Bluetooth MAC address for Classic/BLE transports. */
    suspend fun connect(deviceAddress: String)

    suspend fun disconnect()

    /**
     * Sends a raw ELM327 command (without the trailing carriage return) and returns the
     * response with the '>' prompt and echoed command stripped, lines joined with '\n'.
     */
    suspend fun sendRaw(command: String): String
}

class ObdTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
