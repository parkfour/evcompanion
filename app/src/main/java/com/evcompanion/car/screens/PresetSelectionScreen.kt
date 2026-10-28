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
import com.evcompanion.core.preset.Preset
import kotlinx.coroutines.launch

class PresetSelectionScreen(carContext: CarContext, private val container: AppContainer) : Screen(carContext) {

    private var presets: List<Preset> = emptyList()

    init {
        lifecycleScope.launch {
            container.presetRepository.presets.collect {
                presets = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        presets.forEach { preset ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(preset.displayName)
                    .addText("${preset.rows.size} PIDs")
                    .setOnClickListener {
                        lifecycleScope.launch {
                            container.presetRepository.setActivePreset(preset.id)
                            screenManager.pop()
                        }
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Select preset").setStartHeaderAction(Action.BACK).build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
