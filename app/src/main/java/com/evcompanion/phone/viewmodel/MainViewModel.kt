package com.evcompanion.phone.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evcompanion.App
import com.evcompanion.core.bluetooth.BondedDevice
import com.evcompanion.core.feedback.FeedbackBundle
import com.evcompanion.core.feedback.FeedbackBundleBuilder
import com.evcompanion.core.feedback.FeedbackRequest
import com.evcompanion.core.obd.ObdConnectionState
import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.polling.ObdPollingService
import com.evcompanion.core.preset.PidCategory
import com.evcompanion.core.preset.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    val presets: StateFlow<List<Preset>> = container.presetRepository.presets
    val activePresetId: StateFlow<String?> = container.presetRepository.activePresetId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connectionState: StateFlow<ObdConnectionState> = container.pollingEngine.connectionState
    val values: StateFlow<Map<String, DecodedValue>> = container.pollingEngine.values

    private val _bondedDevices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bondedDevices.asStateFlow()

    private val _scannedBleDevices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val scannedBleDevices: StateFlow<List<BondedDevice>> = _scannedBleDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    init {
        viewModelScope.launch { container.presetRepository.reload() }
        refreshBondedDevices()
    }

    fun refreshBondedDevices() {
        _bondedDevices.value = container.bluetoothDeviceRepository.listBonded()
    }

    /** Many BLE ELM327 clones don't support classic pairing, so they never show up as "bonded". */
    fun scanForBleDevices() = viewModelScope.launch {
        _isScanning.value = true
        _scannedBleDevices.value = container.bluetoothDeviceRepository.scanForBleDevices()
        _isScanning.value = false
    }

    fun selectPreset(preset: Preset) = viewModelScope.launch {
        container.presetRepository.setActivePreset(preset.id)
    }

    fun connect(address: String) {
        ObdPollingService.start(getApplication(), address)
    }

    fun disconnect() {
        ObdPollingService.stop(getApplication())
    }

    fun subscribe(category: PidCategory) = container.pollingEngine.subscribe(category)

    fun unsubscribe(category: PidCategory) = container.pollingEngine.unsubscribe(category)

    val dashboardConfigOverridden: StateFlow<Boolean> = container.dashboardConfigRepository.isOverridden
    val dashboardConfigWarnings: StateFlow<List<String>> = container.dashboardConfigRepository.overrideWarnings

    val activeThemeId: StateFlow<com.evcompanion.core.theme.DashboardThemeId> = container.dashboardLayoutConfig.themeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.evcompanion.core.theme.DashboardThemeId.EV_DASHBOARD)

    fun selectTheme(themeId: com.evcompanion.core.theme.DashboardThemeId) = viewModelScope.launch {
        container.dashboardLayoutConfig.setThemeId(themeId)
    }

    /** Makes [jsonText] the dashboard layout until reset - see DashboardConfigRepository. */
    fun importDashboardConfig(jsonText: String) = viewModelScope.launch {
        container.dashboardConfigRepository.importOverride(jsonText)
    }

    private val _presetImportError = MutableStateFlow<String?>(null)
    val presetImportError: StateFlow<String?> = _presetImportError.asStateFlow()

    /** Saves a Torque-format PID CSV as a user preset and makes it the active one. */
    fun importPreset(fileBaseName: String, csvText: String) = viewModelScope.launch {
        val id = container.presetRepository.importPreset(fileBaseName, csvText)
        _presetImportError.value = if (id == null) "No usable PIDs found in $fileBaseName" else null
        id?.let { container.presetRepository.setActivePreset(it) }
    }

    fun deleteUserPreset(preset: Preset) = viewModelScope.launch {
        container.presetRepository.deleteUserPreset(preset.id)
    }

    val debugFeaturesEnabled: StateFlow<Boolean> = com.evcompanion.core.debug.DebugFileLogger.enabled

    fun setDebugFeaturesEnabled(enabled: Boolean) =
        com.evcompanion.core.debug.DebugFileLogger.setEnabled(getApplication(), enabled)

    val autoScanDtcOnHero: StateFlow<Boolean> = container.dashboardLayoutConfig.autoScanDtcOnHero
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAutoScanDtcOnHero(enabled: Boolean) = viewModelScope.launch {
        container.dashboardLayoutConfig.setAutoScanDtcOnHero(enabled)
    }

    val socFastChargeThreshold: StateFlow<Int> = container.dashboardLayoutConfig.socFastChargeThresholdPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    fun setSocFastChargeThreshold(percent: Int) = viewModelScope.launch {
        container.dashboardLayoutConfig.setSocFastChargeThreshold(percent)
    }

    val minFastChargePowerKw: StateFlow<Int> = container.dashboardLayoutConfig.minFastChargePowerKw
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 100)

    fun setMinFastChargePowerKw(kw: Int) = viewModelScope.launch {
        container.dashboardLayoutConfig.setMinFastChargePowerKw(kw)
    }

    fun resetDashboardConfig() = viewModelScope.launch {
        container.dashboardConfigRepository.clearOverride()
    }

    suspend fun createFeedbackBundle(request: FeedbackRequest): FeedbackBundle {
        return FeedbackBundleBuilder.createBundle(getApplication(), container, request)
    }

    fun shareFeedbackBundle(bundle: FeedbackBundle) {
        FeedbackBundleBuilder.shareBundle(getApplication(), bundle)
    }

    fun openPlayStoreListing() {
        FeedbackBundleBuilder.openPlayStoreListing(getApplication())
    }
}
