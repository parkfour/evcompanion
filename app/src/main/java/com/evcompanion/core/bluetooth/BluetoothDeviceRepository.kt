package com.evcompanion.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

data class BondedDevice(val name: String, val address: String)

/**
 * Lists already-paired Bluetooth Classic devices, and can also run a short BLE scan - many
 * cheap BLE ELM327 clones don't support classic Bluetooth pairing/bonding at all, so they never
 * show up in the bonded-devices list and can only be found this way. Users of a Classic SPP
 * dongle still just pair it once via system Bluetooth settings; [listBonded] covers that case.
 *
 * Both methods are safe to call before the relevant runtime permission has been granted (e.g.
 * on first launch, before the permission dialog resolves) - they return an empty list instead
 * of throwing, since Android throws a runtime SecurityException here, not just a lint warning.
 */
class BluetoothDeviceRepository(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun listBonded(): List<BondedDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { BondedDevice(it.name ?: it.address, it.address) }
        } catch (e: SecurityException) {
            Log.w(TAG, "listBonded() called without BLUETOOTH_CONNECT granted yet", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun scanForBleDevices(durationMillis: Long = 6_000): List<BondedDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()

        val found = linkedMapOf<String, BondedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                found[device.address] = BondedDevice(device.name ?: device.address, device.address)
            }
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.w(TAG, "scanForBleDevices() called without BLUETOOTH_SCAN granted yet", e)
            null
        } ?: return emptyList()

        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "scanForBleDevices() called without BLUETOOTH_SCAN granted yet", e)
            return emptyList()
        }

        try {
            delay(durationMillis)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return found.values.toList()
    }

    companion object {
        private const val TAG = "BluetoothDeviceRepository"
    }
}
