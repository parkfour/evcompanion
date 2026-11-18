package com.evcompanion.core.bm6

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bm6DataStore by preferencesDataStore(name = "bm6_device_prefs")
private val BM6_ADDRESS_KEY = stringPreferencesKey("bm6_address")

/** The one BM6 the user picked - never auto-chosen, since a neighbour's BM6 advertises the same name. */
class Bm6DeviceRepository(private val context: Context) {
    val address: Flow<String?> = context.bm6DataStore.data.map { it[BM6_ADDRESS_KEY] }

    suspend fun save(address: String?) {
        context.bm6DataStore.edit { if (address == null) it.remove(BM6_ADDRESS_KEY) else it[BM6_ADDRESS_KEY] = address }
    }
}
