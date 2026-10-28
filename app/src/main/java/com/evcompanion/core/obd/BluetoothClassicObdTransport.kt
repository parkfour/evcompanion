package com.evcompanion.core.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.evcompanion.core.debug.DebugFileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Talks to a Classic Bluetooth (RFCOMM/SPP) ELM327-compatible adapter. BLE-only adapters are
 * not supported by this transport - see [ObdTransport] for the abstraction that would let a
 * BLE implementation be added later without touching callers.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller before connect()
class BluetoothClassicObdTransport(context: Context) : ObdTransport {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(deviceAddress: String) = withContext(Dispatchers.IO) {
        _connectionState.value = ObdConnectionState.Connecting
        val adapter = bluetoothAdapter
            ?: run {
                _connectionState.value = ObdConnectionState.Error("Bluetooth is not available on this device")
                throw ObdTransportException("Bluetooth is not available on this device")
            }
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = ObdConnectionState.Error("Invalid Bluetooth address: $deviceAddress")
            throw ObdTransportException("Invalid Bluetooth address: $deviceAddress", e)
        }

        // Cheap ELM327 clones frequently fail SSP (secure) pairing confirmation; fall back to
        // an insecure RFCOMM socket if the secure one can't connect. Both attempts are time-
        // bounded - see connectWithTimeout's doc for why a bare BluetoothSocket.connect() can't
        // be trusted to fail on its own.
        val connected = runCatching {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            s.connectWithTimeout(SOCKET_TIMEOUT_MILLIS)
            s
        }.getOrElse { secureError ->
            DebugFileLogger.w(TAG, "connect($deviceAddress): secure RFCOMM failed (${secureError.message}), trying insecure")
            runCatching {
                val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                s.connectWithTimeout(SOCKET_TIMEOUT_MILLIS)
                s
            }.getOrElse { insecureError ->
                DebugFileLogger.e(TAG, "connect($deviceAddress): insecure RFCOMM also failed: ${insecureError.message}")
                _connectionState.value = ObdConnectionState.Error(
                    insecureError.message ?: "Failed to connect to $deviceAddress"
                )
                throw ObdTransportException("Failed to connect to $deviceAddress", insecureError)
            }
        }

        socket = connected
        input = java.io.BufferedInputStream(connected.inputStream, 1024)
        output = connected.outputStream
        _connectionState.value = ObdConnectionState.Connected
        DebugFileLogger.i(TAG, "connect($deviceAddress): socket connected")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        DebugFileLogger.i(TAG, "disconnect()")
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        _connectionState.value = ObdConnectionState.Disconnected
    }

    override suspend fun sendRaw(command: String): String {
        val out = output ?: throw ObdTransportException("Not connected")
        val inp = input ?: throw ObdTransportException("Not connected")
        return try {
            withContext(Dispatchers.IO) {
                drainStaleInput(inp)
                out.write((command + "\r").toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            DebugFileLogger.i(TAG, "TX: $command")
            val response = readUntilPrompt(inp)
            DebugFileLogger.i(TAG, "RX: ${response.replace("\r", "\\r").replace("\n", "\\n")}")
            response
        } catch (e: IOException) {
            DebugFileLogger.e(TAG, "TX: $command -> I/O error: ${e.message}")
            _connectionState.value = ObdConnectionState.Error(e.message ?: "I/O error")
            throw ObdTransportException("I/O error talking to adapter", e)
        } catch (e: ObdTransportException) {
            DebugFileLogger.e(TAG, "TX: $command -> ${e.message}")
            throw e
        }
    }

    private fun drainStaleInput(input: InputStream) {
        try {
            while (input.available() > 0) {
                input.read()
            }
        } catch (e: IOException) {
            // Nothing to drain if the stream's already dead - the next real read will surface it.
        }
    }

    /**
     * `InputStream.read()` on a BluetoothSocket is a plain blocking call with no cooperative
     * cancellation. A watchdog coroutine force-closes the socket on timeout, which reliably
     * unblocks the stuck read with an IOException. Uses a chunked buffer for high read throughput.
     */
    private suspend fun readUntilPrompt(input: InputStream): String = coroutineScope {
        val watchdog = launch {
            delay(COMMAND_TIMEOUT_MILLIS)
            runCatching { socket?.close() }
        }
        try {
            withContext(Dispatchers.IO) {
                val buffer = StringBuilder()
                val byteBuf = ByteArray(256)
                while (true) {
                    val readCount = input.read(byteBuf)
                    if (readCount == -1) throw ObdTransportException("Adapter closed the connection")
                    var foundPrompt = false
                    for (i in 0 until readCount) {
                        val c = byteBuf[i].toInt().toChar()
                        if (c == '>') {
                            foundPrompt = true
                            break
                        }
                        buffer.append(c)
                    }
                    if (foundPrompt) break
                }
                buffer.toString()
            }
        } finally {
            watchdog.cancel()
        }
    }

    /** Same watchdog-close pattern as [readUntilPrompt] - see its doc for why a plain timeout doesn't work here. */
    private suspend fun BluetoothSocket.connectWithTimeout(timeoutMillis: Long) = coroutineScope {
        val watchdog = launch {
            delay(timeoutMillis)
            runCatching { close() }
        }
        try {
            withContext(Dispatchers.IO) { connect() }
        } finally {
            watchdog.cancel()
        }
    }

    companion object {
        private const val TAG = "BluetoothClassicObdTransport"

        /** Well-known Serial Port Profile UUID, standard across all Bluetooth Classic SPP devices. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SOCKET_TIMEOUT_MILLIS = 8000L
        private const val COMMAND_TIMEOUT_MILLIS = 3000L
    }
}
