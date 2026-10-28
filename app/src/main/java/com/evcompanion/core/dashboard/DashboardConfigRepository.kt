package com.evcompanion.core.dashboard

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Loads the dashboard layout from `assets/dashboards/default.json` on startup, unless the user
 * has imported their own JSON (e.g. picked via the phone UI's file picker) - that's saved to
 * `filesDir/dashboards/override.json` and stays in effect across restarts until [clearOverride],
 * so someone with a different car can tune a layout and keep it.
 */
class DashboardConfigRepository(private val context: Context) {

    private val overrideFile: File by lazy { File(context.filesDir, "dashboards/override.json") }

    private val _activeConfig = MutableStateFlow<DashboardConfig?>(null)
    val activeConfig: StateFlow<DashboardConfig?> = _activeConfig.asStateFlow()

    private val _overrideWarnings = MutableStateFlow<List<String>>(emptyList())
    /** Warnings from the active override (import or saved) - e.g. to surface in the phone UI. */
    val overrideWarnings: StateFlow<List<String>> = _overrideWarnings.asStateFlow()

    private val _isOverridden = MutableStateFlow(false)
    val isOverridden: StateFlow<Boolean> = _isOverridden.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) {
        // Caught rather than left to propagate: this runs fire-and-forget from AppContainer's
        // init (and from clearOverride), with no CoroutineExceptionHandler installed anywhere -
        // an uncaught exception here (e.g. the asset somehow missing) would otherwise crash the
        // whole app on startup instead of just leaving the dashboard unconfigured.
        if (overrideFile.exists()) {
            val result = runCatching { DashboardConfigParser.parse(overrideFile.readText()) }
                .onFailure { Log.e(TAG, "Failed to load saved override", it) }
                .getOrNull()
            if (result?.config != null) {
                _activeConfig.value = result.config
                _isOverridden.value = true
                _overrideWarnings.value = result.warnings
                return@withContext
            }
        }
        try {
            val text = context.assets.open(DEFAULT_ASSET_PATH).bufferedReader().use { it.readText() }
            val result = DashboardConfigParser.parse(text)
            result.warnings.forEach { Log.w(TAG, "default.json: $it") }
            _activeConfig.value = result.config
            _isOverridden.value = false
            _overrideWarnings.value = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $DEFAULT_ASSET_PATH", e)
        }
    }

    /**
     * Parses [jsonText] and, if it yields any usable config, makes it the active one and saves it
     * as the override - see the class doc. Returns the parse warnings either way, so the caller can show them
     * even on success (a config can load with some tiles skipped and still be "successful" enough
     * to use - same leniency as [DashboardConfigParser]).
     */
    suspend fun importOverride(jsonText: String): List<String> = withContext(Dispatchers.IO) {
        val result = DashboardConfigParser.parse(jsonText)
        _overrideWarnings.value = result.warnings
        if (result.config != null) {
            overrideFile.parentFile?.mkdirs()
            overrideFile.writeText(jsonText)
            _activeConfig.value = result.config
            _isOverridden.value = true
        }
        result.warnings
    }

    /** Deletes the saved override and reverts to the bundled default without a restart. */
    suspend fun clearOverride() {
        withContext(Dispatchers.IO) { overrideFile.delete() }
        reload()
    }

    companion object {
        private const val TAG = "DashboardConfigRepository"
        private const val DEFAULT_ASSET_PATH = "dashboards/default.json"
    }
}
