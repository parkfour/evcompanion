package com.evcompanion.core.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.evcompanion.core.debug.DebugFileLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Talks to a BLE ELM327-compatible adapter over GATT. Unlike Classic Bluetooth SPP, there is no
 * single universal BLE OBD service UUID - cheap adapters are built around a handful of common
 * "transparent UART" reference designs, so this tries known service UUIDs first (Nordic UART
 * Service, Vgate iCar Pro BLE, the FFF0 pattern used by Veepeak and others, the HM-10/CC254x
 * FFE0 pattern) and falls back to a generic scan for any writable characteristic paired with a
 * notifiable one. If none of that matches, [connect] fails with a clear error rather than
 * silently doing nothing.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller before connect()
class BleObdTransport(private val context: Context) : ObdTransport {

    private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var connectSignal: CompletableDeferred<Unit>? = null
    private var pendingResponse: CompletableDeferred<String>? = null
    private val incomingBuffer = StringBuilder()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ObdConnectionState.Initializing
                    // Request a larger MTU before discovering services - the default (23 bytes,
                    // ~20 usable per packet) means a 30+ byte multi-frame UDS response (e.g. a
                    // cell-voltage group) needs several round trips to arrive, which was slow
                    // enough under real driving RF conditions to blow past the response
                    // timeout. onMtuChanged (below) continues on to discoverServices() either
                    // way, so a peripheral that ignores/rejects this still connects normally.
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ObdConnectionState.Disconnected
                    connectSignal?.completeExceptionally(ObdTransportException("Disconnected (status=$status)"))
                    pendingResponse?.completeExceptionally(ObdTransportException("Disconnected while waiting for a response"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status services=${g.services.map { it.uuid }}")
            val found = findUartCharacteristics(g)
            if (found == null) {
                val message = "No writable/notifiable characteristic found - this BLE adapter " +
                    "uses a GATT profile this app doesn't recognize yet"
                Log.w(TAG, message)
                _connectionState.value = ObdConnectionState.Error(message)
                connectSignal?.completeExceptionally(ObdTransportException(message))
                return
            }
            val (write, notify) = found
            Log.d(TAG, "Using service=${write.service.uuid} write=${write.uuid} notify=${notify.uuid}")
            writeCharacteristic = write
            notifyCharacteristic = notify
            g.setCharacteristicNotification(notify, true)

            val cccd = notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd == null) {
                // No standard notification descriptor - assume notifications are already live.
                _connectionState.value = ObdConnectionState.Connected
                connectSignal?.complete(Unit)
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptorCompat(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            _connectionState.value = ObdConnectionState.Connected
            connectSignal?.complete(Unit)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(value)
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        incomingBuffer.append(String(bytes, Charsets.US_ASCII))
        val promptIndex = incomingBuffer.indexOf(">")
        if (promptIndex >= 0) {
            val response = incomingBuffer.substring(0, promptIndex)
            incomingBuffer.delete(0, promptIndex + 1)
            pendingResponse?.complete(response)
            pendingResponse = null
        }
    }

    override suspend fun connect(deviceAddress: String) {
        Log.d(TAG, "connect($deviceAddress)")
        DebugFileLogger.i(TAG, "connect($deviceAddress)")
        _connectionState.value = ObdConnectionState.Connecting
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: run {
                _connectionState.value = ObdConnectionState.Error("Bluetooth is not available on this device")
                DebugFileLogger.e(TAG, "Bluetooth is not available on this device")
                throw ObdTransportException("Bluetooth is not available on this device")
            }
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = ObdConnectionState.Error("Invalid Bluetooth address: $deviceAddress")
            DebugFileLogger.e(TAG, "Invalid Bluetooth address: $deviceAddress")
            throw ObdTransportException("Invalid Bluetooth address: $deviceAddress", e)
        }

        val signal = CompletableDeferred<Unit>()
        connectSignal = signal
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        try {
            withTimeout(15_000) { signal.await() }
            Log.d(TAG, "connect($deviceAddress) succeeded")
            DebugFileLogger.i(TAG, "connect($deviceAddress) succeeded")
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "connect($deviceAddress) timed out")
            DebugFileLogger.w(TAG, "connect($deviceAddress) timed out")
            _connectionState.value = ObdConnectionState.Error("Timed out connecting to $deviceAddress")
            throw ObdTransportException("Timed out connecting to $deviceAddress", e)
        } catch (e: ObdTransportException) {
            Log.w(TAG, "connect($deviceAddress) failed: ${e.message}")
            DebugFileLogger.e(TAG, "connect($deviceAddress) failed: ${e.message}")
            throw e
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        DebugFileLogger.i(TAG, "disconnect()")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        incomingBuffer.setLength(0)
        _connectionState.value = ObdConnectionState.Disconnected
    }

    override suspend fun sendRaw(command: String): String {
        val g = gatt ?: throw ObdTransportException("Not connected")
        val writeChar = writeCharacteristic ?: throw ObdTransportException("Not connected")

        val deferred = CompletableDeferred<String>()
        pendingResponse = deferred
        val payload = (command + "\r").toByteArray(Charsets.US_ASCII)
        DebugFileLogger.i(TAG, "TX: $command")
        writeCharacteristicCompat(g, writeChar, payload)

        return try {
            // Multi-frame UDS responses (cell voltage groups, etc.) plus real-world driving RF
            // conditions can push a single request-response round trip well past a
            // few seconds - too short a timeout here caused every query to fail with
            // a timeout/STOPPED cascade during actual in-car testing (see project notes).
            val response = withTimeout(15_000) { deferred.await() }
            DebugFileLogger.i(TAG, "RX: ${response.replace("\r", "\\r").replace("\n", "\\n")}")
            response
        } catch (e: TimeoutCancellationException) {
            DebugFileLogger.e(TAG, "TX: $command -> Timed out waiting for response")
            throw ObdTransportException("Timed out waiting for a response to '$command'", e)
        } catch (e: Exception) {
            DebugFileLogger.e(TAG, "TX: $command -> error: ${e.message}")
            throw e
        }
    }

    private fun writeCharacteristicCompat(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    /**
     * Tries the Nordic UART Service first (its RX/TX characteristic roles are spec-defined, so
     * they're safe to hardcode), then a prioritized list of other known service UUIDs - within
     * those, write/notify are picked by GATT *property*, not by characteristic UUID, because
     * vendors disagree on which UUID plays which role (e.g. FFF1 is notify on some adapters and
     * write on others). Finally falls back to scanning every discovered service the same way.
     */
    private fun findUartCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        g.getService(NORDIC_UART_SERVICE)?.let { service ->
            val write = service.getCharacteristic(NORDIC_UART_RX)
            val notify = service.getCharacteristic(NORDIC_UART_TX)
            if (write != null && notify != null) return write to notify
        }

        val knownServices = KNOWN_SERVICE_UUIDS.mapNotNull { g.getService(it) }
        val otherServices = g.services.filter { it.uuid !in KNOWN_SERVICE_UUIDS && it.uuid != NORDIC_UART_SERVICE }
        for (service in knownServices + otherServices) {
            val write = service.findWritable()
            val notify = service.findNotifiable()
            if (write != null && notify != null) return write to notify
        }

        val anyWrite = g.services.firstNotNullOfOrNull { it.findWritable() }
        val anyNotify = g.services.firstNotNullOfOrNull { it.findNotifiable() }
        return if (anyWrite != null && anyNotify != null) anyWrite to anyNotify else null
    }

    private fun BluetoothGattService.findWritable(): BluetoothGattCharacteristic? =
        characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

    private fun BluetoothGattService.findNotifiable(): BluetoothGattCharacteristic? =
        characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

    companion object {
        private const val TAG = "BleObdTransport"

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Nordic UART Service (NUS) - an extremely common reference design many BLE-UART
        // bridge chips clone; write is the "RX" characteristic from the peripheral's point of
        // view, notify is "TX". These roles are part of the published spec, unlike the
        // UUIDs below.
        private val NORDIC_UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NORDIC_UART_RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NORDIC_UART_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        // Other known BLE OBD adapter service UUIDs, checked before a fully generic scan of
        // every service. Characteristics within them are still picked by GATT property (see
        // findUartCharacteristics) since vendors disagree on which characteristic UUID is
        // write vs notify even within the same nominal service.
        private val VGATE_ICAR_PRO_SERVICE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        private val FFF0_SERVICE = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB") // Veepeak and other common clones
        private val FFE0_SERVICE = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB") // HM-10/CC254x "transparent UART" clones

        private val KNOWN_SERVICE_UUIDS = listOf(VGATE_ICAR_PRO_SERVICE, FFF0_SERVICE, FFE0_SERVICE)
    }
}
