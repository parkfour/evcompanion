package com.evcompanion

import android.content.Context
import com.evcompanion.core.bluetooth.BluetoothDeviceRepository
import com.evcompanion.core.bluetooth.LastObdDeviceRepository
import com.evcompanion.core.dashboard.DashboardConfigRepository
import com.evcompanion.core.location.VehicleLocationProvider
import com.evcompanion.core.obd.ObdTransportFactory
import com.evcompanion.core.poi.EvChargerRepository
import com.evcompanion.core.polling.ObdPollingService
import com.evcompanion.core.polling.PollingEngine
import com.evcompanion.core.preset.Preset
import com.evcompanion.core.preset.PresetRepository
import com.evcompanion.core.settings.DashboardLayoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Small hand-rolled DI container - no framework needed for a project this size. */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val presetRepository = PresetRepository(context)
    val bluetoothDeviceRepository = BluetoothDeviceRepository(context)
    val lastObdDeviceRepository = LastObdDeviceRepository(context)
    val dashboardLayoutConfig = DashboardLayoutConfig(context)
    val dashboardConfigRepository = DashboardConfigRepository(context)
    val vehicleLocationProvider = VehicleLocationProvider(context)
    val evChargerRepository = EvChargerRepository(context, vehicleLocationProvider, appScope)
    val pollingEngine = PollingEngine(
        transportFactory = ObdTransportFactory(context),
        scope = appScope,
        lastDeviceRepository = lastObdDeviceRepository,
    )

    init {
        // Loaded here (not lazily from a ViewModel, unlike presetRepository) since the car-side
        // DashboardScreen depends on it directly and may be the very first screen shown - it
        // can't rely on the phone UI having opened first.
        appScope.launch { dashboardConfigRepository.reload() }

        // Single source of truth: whichever Preset object matches the repository's active id,
        // merged with the active dashboard config's own customPids (see DashboardConfigRepository's
        // override doc), always drives the polling engine - so any UI (phone or car)
        // only ever needs to call presetRepository.setActivePreset(id) or import a dashboard
        // config; neither has to remember to push the resolved Preset into the polling engine
        // itself.
        appScope.launch {
            combine(
                presetRepository.presets,
                presetRepository.activePresetId,
                dashboardConfigRepository.activeConfig,
            ) { presets, id, dashboardConfig ->
                val base = presets.firstOrNull { it.id == id } ?: return@combine null
                val customRows = dashboardConfig?.customPids.orEmpty().map { it.toPidRow() }
                if (customRows.isEmpty()) base else Preset(base.id, base.displayName, base.rows + customRows)
            }.filterNotNull().collect { pollingEngine.setActivePreset(it) }
        }

        // Reconnect to whatever adapter last actually worked, so the driver doesn't have to
        // re-pick it from the Connect screen every drive. The Connect screen still lets them stop
        // this (or any) attempt and pick a different device - see PollingEngine.disconnect.
        appScope.launch {
            lastObdDeviceRepository.lastDeviceAddress.first()?.let { address ->
                ObdPollingService.start(context, address)
            }
        }
    }
}
