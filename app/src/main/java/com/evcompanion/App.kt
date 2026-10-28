package com.evcompanion

import android.app.Application
import com.evcompanion.core.debug.DebugFileLogger

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DebugFileLogger.init(this)
        container = AppContainer(this)
    }
}
