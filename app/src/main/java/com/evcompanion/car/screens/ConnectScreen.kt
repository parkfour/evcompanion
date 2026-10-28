package com.evcompanion.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.evcompanion.AppContainer
import com.evcompanion.core.bluetooth.BondedDevice
import com.evcompanion.core.obd.ObdConnectionState
import com.evcompanion.core.polling.ObdPollingService
import kotlinx.coroutines.launch

/**
 * Lists paired Classic Bluetooth devices plus a "Scan for BLE devices" action - many cheap BLE
 * ELM327 clones don't support classic pairing at all, so they'd never appear otherwise. See
 * BluetoothDeviceRepository.scanForBleDevices().
 */
class ConnectScreen(carContext: CarContext, private val container: AppContainer) : Screen(carContext) {

    private var scannedDevices: List<BondedDevice> = emptyList()
    private var isScanning = false
    private var connectingAddress: String? = null
    private var connectionState: ObdConnectionState = ObdConnectionState.Disconnected

    init {
        lifecycleScope.launch {
            container.pollingEngine.connectionState.collect { state ->
                connectionState = state
                if (state !is ObdConnectionState.Connecting && state !is ObdConnectionState.Initializing) {
                    connectingAddress = null
                }
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val isConnecting = connectionState is ObdConnectionState.Connecting || connectionState is ObdConnectionState.Initializing

        // Reflects the shared connection state, not just taps made from this screen instance, so
        // an attempt started elsewhere (e.g. the auto-reconnect on app launch - see AppContainer)
        // still shows a way to stop it here.
        if (isConnecting) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Stop connecting")
                    .setOnClickListener { ObdPollingService.stop(carContext) }
                    .build()
            )
        }

        listBuilder.addItem(
            Row.Builder()
                .setTitle(if (isScanning) "Scanning..." else "Scan for BLE devices")
                .setOnClickListener { startScan() }
                .build()
        )

        val devices = (container.bluetoothDeviceRepository.listBonded() + scannedDevices).distinctBy { it.address }
        if (devices.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("No devices found")
                    .addText("Pair a Classic adapter in phone Bluetooth settings, or scan for a BLE one")
                    .build()
            )
        }
        devices.forEach { device ->
            val isConnectingThis = connectingAddress == device.address
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(device.name)
                    .addText(if (isConnectingThis) "Connecting..." else device.address)
                    .setOnClickListener {
                        // Tapping any device - including one different from an attempt already in
                        // flight - cancels whatever was connecting and starts fresh; see
                        // PollingEngine.connectAsync.
                        connectingAddress = device.address
                        invalidate()
                        ObdPollingService.start(carContext, device.address)
                        screenManager.pop()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Connect").setStartHeaderAction(Action.BACK).build())
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        invalidate()
        lifecycleScope.launch {
            scannedDevices = container.bluetoothDeviceRepository.scanForBleDevices()
            isScanning = false
            invalidate()
        }
    }
}
