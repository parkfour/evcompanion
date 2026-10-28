package com.evcompanion.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.lifecycleScope
import com.evcompanion.App
import com.evcompanion.car.screens.DashboardScreen
import kotlinx.coroutines.launch

class EvCompanionSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val container = (carContext.applicationContext as App).container
        // The car host may bind before the phone Activity ever ran, so make sure presets are
        // loaded here too rather than assuming MainActivity already did it.
        lifecycleScope.launch { container.presetRepository.reload() }
        return DashboardScreen(carContext, container)
    }
}
