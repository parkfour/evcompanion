package com.evcompanion.core.bluetooth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lastObdDeviceDataStore by preferencesDataStore(name = "last_obd_device_prefs")
private val LAST_DEVICE_ADDRESS_KEY = stringPreferencesKey("last_device_address")

/**
 * Remembers the last OBD adapter address a connection actually succeeded on (data flowed, not
 * just the transport socket opening - see PollingEngine.connect), so the app can try it again
 * automatically on the next launch instead of making the driver re-pick it from the Connect
 * screen every time.
 */
class LastObdDeviceRepository(private val context: Context) {
    val lastDeviceAddress: Flow<String?> = context.lastObdDeviceDataStore.data.map { it[LAST_DEVICE_ADDRESS_KEY] }

    suspend fun save(address: String) {
        context.lastObdDeviceDataStore.edit { it[LAST_DEVICE_ADDRESS_KEY] = address }
    }
}
