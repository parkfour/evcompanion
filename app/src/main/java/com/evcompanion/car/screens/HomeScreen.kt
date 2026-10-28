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
import com.evcompanion.core.obd.ObdConnectionState
import com.evcompanion.core.preset.Preset
import com.evcompanion.core.theme.DashboardTheme
import com.evcompanion.core.theme.DefaultDashboardTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Root car screen: connection status, active preset, display theme, and one row per category in that preset.
 */
class HomeScreen(carContext: CarContext, private val container: AppContainer) : Screen(carContext) {

    private var connectionState: ObdConnectionState = ObdConnectionState.Disconnected
    private var activePreset: Preset? = null
    private var activeTheme: DashboardTheme = DefaultDashboardTheme

    init {
        lifecycleScope.launch {
            container.pollingEngine.connectionState.collect {
                connectionState = it
                invalidate()
            }
        }
        lifecycleScope.launch {
            combine(container.presetRepository.presets, container.presetRepository.activePresetId) { presets, id ->
                presets.firstOrNull { it.id == id } ?: presets.firstOrNull()
            }.collect {
                activePreset = it
                invalidate()
            }
        }
        lifecycleScope.launch {
            container.dashboardLayoutConfig.activeTheme.collect {
                activeTheme = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Connection")
                .addText(statusText(connectionState))
                .setOnClickListener { screenManager.push(ConnectScreen(carContext, container)) }
                .build()
        )

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Preset")
                .addText(activePreset?.displayName ?: "None selected - tap to choose")
                .setOnClickListener { screenManager.push(PresetSelectionScreen(carContext, container)) }
                .build()
        )

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Display Theme")
                .addText(activeTheme.displayName)
                .setOnClickListener {
                    lifecycleScope.launch { container.dashboardLayoutConfig.cycleTheme() }
                }
                .build()
        )

        activePreset?.categories?.forEach { category ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(category.label)
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(LiveDataScreen(carContext, container, category)) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("EV Companion").setStartHeaderAction(Action.BACK).build())
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun statusText(state: ObdConnectionState): String = when (state) {
        ObdConnectionState.Disconnected -> "Not connected - tap to connect"
        ObdConnectionState.Connecting -> "Connecting..."
        ObdConnectionState.Initializing -> "Initializing adapter..."
        ObdConnectionState.Connected -> "Connected"
        is ObdConnectionState.Error -> "Error: ${state.message}"
    }
}
