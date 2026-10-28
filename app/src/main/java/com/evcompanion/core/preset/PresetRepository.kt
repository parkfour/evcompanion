package com.evcompanion.core.preset

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.presetPrefsDataStore by preferencesDataStore(name = "preset_prefs")

private val BUNDLED_PRESET_NAMES = mapOf(
    "ioniq5_awd_77kwh" to "Hyundai IONIQ 5 AWD 77kWh",
    "generic_ev" to "Generic EV (standard SAE PIDs)",
)

/**
 * Loads Torque-CSV presets from two sources: read-only bundled assets under `assets/presets`
 * and user-imported/authored presets under `filesDir/presets` - see [Preset]. The CSV file
 * itself doubles as the import/export format: a preset a user finds or edits elsewhere is a
 * plain CSV drop-in.
 */
class PresetRepository(private val context: Context) {

    private val userPresetsDir: File by lazy { File(context.filesDir, "presets").apply { mkdirs() } }

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    val activePresetId: Flow<String?> = context.presetPrefsDataStore.data.map { it[ACTIVE_PRESET_KEY] }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<Preset>()

        context.assets.list("presets")?.filter { it.endsWith(".csv") }?.forEach { fileName ->
            val text = context.assets.open("presets/$fileName").bufferedReader().use { it.readText() }
            val id = fileName.removeSuffix(".csv")
            loaded += loadPreset(id, BUNDLED_PRESET_NAMES[id] ?: prettify(id), text)
        }

        userPresetsDir.listFiles { f -> f.extension == "csv" }?.forEach { file ->
            val id = "user_${file.nameWithoutExtension}"
            loaded += loadPreset(id, prettify(file.nameWithoutExtension), file.readText())
        }

        _presets.value = loaded

        // First run: nothing is queried until a preset is active, so a first-time user who
        // only connects the adapter and never visits the Preset tab would see no data at all.
        // Default to the IONIQ 5 preset (falling back to whatever loaded first) rather than
        // requiring an explicit choice before anything works.
        if (loaded.isNotEmpty() && activePresetId.first() == null) {
            val default = loaded.firstOrNull { it.id == "ioniq5_awd_77kwh" } ?: loaded.first()
            setActivePreset(default.id)
        }
    }

    suspend fun setActivePreset(id: String) = withContext(Dispatchers.IO) {
        context.presetPrefsDataStore.edit { it[ACTIVE_PRESET_KEY] = id }
    }

    /**
     * Saves [csvText] as a user preset named [fileBaseName] (overwriting one of the same name, so
     * re-importing an edited file just updates it), reloads [presets], and returns its id - or
     * null, saving nothing, if the CSV yields no usable PID rows.
     */
    suspend fun importPreset(fileBaseName: String, csvText: String): String? = withContext(Dispatchers.IO) {
        if (TorquePresetParser.parse(csvText).rows.isEmpty()) return@withContext null
        val safeName = fileBaseName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(userPresetsDir, "$safeName.csv").writeText(csvText)
        reload()
        "user_$safeName"
    }

    suspend fun deleteUserPreset(id: String) = withContext(Dispatchers.IO) {
        val baseName = id.removePrefix("user_")
        File(userPresetsDir, "$baseName.csv").delete()
        // Deleting the active preset would otherwise leave nothing polled - clear it so reload()
        // falls back to the bundled default.
        context.presetPrefsDataStore.edit { if (it[ACTIVE_PRESET_KEY] == id) it.remove(ACTIVE_PRESET_KEY) }
        reload()
    }

    private fun loadPreset(id: String, displayName: String, csvText: String): Preset {
        val result = TorquePresetParser.parse(csvText)
        result.warnings.forEach { Log.w(TAG, "Preset '$id': $it") }
        return Preset(id, displayName, result.rows)
    }

    private fun prettify(id: String): String =
        id.replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        private const val TAG = "PresetRepository"
        private val ACTIVE_PRESET_KEY = stringPreferencesKey("active_preset_id")
    }
}
