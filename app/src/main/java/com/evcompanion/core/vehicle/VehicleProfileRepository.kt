package com.evcompanion.core.vehicle

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Finds the vehicle profile for a preset: `filesDir/vehicles/<presetId>.json` (a user-supplied
 * profile for an imported preset), then `assets/vehicles/<presetId>.json`, then
 * `assets/vehicles/default.json`, then the generic [VehicleProfile] defaults - so a broken or
 * missing file never stops the app from polling.
 */
class VehicleProfileRepository(private val context: Context) {

    fun load(presetId: String?): VehicleProfile {
        val id = presetId?.removePrefix("user_")
        val text = id?.let { readUser(it) ?: readAsset("vehicles/$it.json") } ?: readAsset(DEFAULT_ASSET)
        val result = text?.let { VehicleProfileParser.parse(it) }
        result?.warnings?.forEach { Log.w(TAG, "Profile for '$presetId': $it") }
        return result?.profile ?: VehicleProfile()
    }

    /**
     * Saves [jsonText] as the profile for [presetId] (imported from the phone UI), replacing any
     * earlier import. Returns the parse warnings; a file that isn't valid JSON is not saved.
     */
    fun import(presetId: String?, jsonText: String): List<String> {
        val result = VehicleProfileParser.parse(jsonText)
        if (result.profile != null) {
            userFile(presetId).apply { parentFile?.mkdirs() }.writeText(jsonText)
        }
        return result.warnings
    }

    /** Drops an imported profile for [presetId], reverting to the bundled one. */
    fun clear(presetId: String?) {
        userFile(presetId).delete()
    }

    fun hasImported(presetId: String?): Boolean = userFile(presetId).exists()

    private fun userFile(presetId: String?): File =
        File(context.filesDir, "vehicles/${presetId?.removePrefix("user_") ?: "default"}.json")

    private fun readUser(id: String): String? =
        File(context.filesDir, "vehicles/$id.json").takeIf { it.exists() }?.readText()

    private fun readAsset(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    companion object {
        private const val TAG = "VehicleProfileRepo"
        private const val DEFAULT_ASSET = "vehicles/default.json"
    }
}
