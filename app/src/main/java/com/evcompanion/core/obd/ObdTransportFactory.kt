package com.evcompanion.core.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.File

/**
 * Picks the right [ObdTransport] for a device address at connect time, based on Android's own
 * record of whether that device is Classic or BLE (populated from bonding and/or prior scan
 * results) - see [BluetoothClassicObdTransport] and [BleObdTransport]. This is the seam the
 * rest of the app was built around so BLE support could be added without touching callers - a
 * [ReplayObdTransport] over a captured debug log hooks in here the same way.
 */
open class ObdTransportFactory(private val context: Context?) {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before any connect() call
    open fun create(deviceAddress: String): ObdTransport {
        if (deviceAddress.startsWith(ReplayObdTransport.ADDRESS_PREFIX)) {
            return ReplayObdTransport.fromFile(File(deviceAddress.removePrefix(ReplayObdTransport.ADDRESS_PREFIX)))
        }
        val ctx = context ?: return FakeObdTransport()
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val type = runCatching { adapter?.getRemoteDevice(deviceAddress)?.type }.getOrNull()
        return if (type == BluetoothDevice.DEVICE_TYPE_LE) {
            BleObdTransport(ctx)
        } else {
            BluetoothClassicObdTransport(ctx)
        }
    }
}
