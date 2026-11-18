package com.evcompanion.core.bm6

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.preset.PidCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Keeps a background GATT link to the user's BM6 and publishes each reading via [onValues] as
 * "BM6 Voltage"/"BM6 SOC"/"BM6 Temperature". Independent of the OBD link: it uses autoConnect, so
 * Android reconnects on its own whenever the monitor is back in range, and a missing BM6 never
 * blocks or errors anything else - its values are simply absent.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller (ObdPollingService) first
class Bm6Monitor(
    private val context: Context,
    private val onValues: (Map<String, DecodedValue>) -> Unit,
) {
    enum class State { OFF, SEARCHING, CONNECTED, RECEIVING }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            DebugFileLogger.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = State.CONNECTED
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && gatt === g) {
                // autoConnect keeps the request alive - Android reconnects when back in range.
                _state.value = State.SEARCHING
                onValues(emptyMap())
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Bm6Protocol.SERVICE_UUID)
            val notify = service?.getCharacteristic(Bm6Protocol.NOTIFY_UUID)
            if (notify == null) {
                DebugFileLogger.w(TAG, "no FFF0/FFF4 on device - not a BM6? services=${g.services.map { it.uuid }}")
                return
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID) ?: return sendStart(g)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        // The start command has to wait for the CCCD write - GATT allows one outstanding op.
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = sendStart(g)

        @Deprecated("Pre-33 callback", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            handle(value)
    }

    private fun sendStart(g: BluetoothGatt) {
        val write = g.getService(Bm6Protocol.SERVICE_UUID)?.getCharacteristic(Bm6Protocol.WRITE_UUID) ?: return
        val command = Bm6Protocol.startCommand
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(write, command, type)
        } else {
            @Suppress("DEPRECATION")
            write.value = command
            write.writeType = type
            @Suppress("DEPRECATION")
            g.writeCharacteristic(write)
        }
    }

    private fun handle(packet: ByteArray) {
        val reading = Bm6Protocol.parse(packet) ?: return
        _state.value = State.RECEIVING
        val now = System.currentTimeMillis()
        fun dv(name: String, short: String, value: Double, unit: String) =
            name to DecodedValue(name, short, PidCategory.BATTERY, value, unit, now)
        onValues(
            mapOf(
                dv(VOLTAGE, "BM6 V", reading.voltage, "V"),
                dv(SOC, "BM6 SOC", reading.socPercent, "%"),
                dv(TEMPERATURE, "BM6 TEMP", reading.temperatureC, "°C"),
            ),
        )
    }

    /** (Re)starts the background link to [address]; replaces any previous one. */
    @Synchronized
    fun start(address: String) {
        stop()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        DebugFileLogger.i(TAG, "starting BM6 link to $address")
        _state.value = State.SEARCHING
        gatt = try {
            device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            DebugFileLogger.w(TAG, "no BLUETOOTH_CONNECT permission: ${e.message}")
            _state.value = State.OFF
            null
        }
    }

    @Synchronized
    fun stop() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        _state.value = State.OFF
        onValues(emptyMap())
    }

    companion object {
        private const val TAG = "Bm6Monitor"
        const val VOLTAGE = "BM6 Voltage"
        const val SOC = "BM6 SOC"
        const val TEMPERATURE = "BM6 Temperature"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
