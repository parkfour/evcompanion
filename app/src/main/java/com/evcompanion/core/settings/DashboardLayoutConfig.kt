package com.evcompanion.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evcompanion.core.theme.DashboardTheme
import com.evcompanion.core.theme.DashboardThemeId
import com.evcompanion.core.theme.DashboardThemeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dashboardLayoutDataStore by preferencesDataStore(name = "dashboard_layout_prefs")
private val DRIVER_ON_RIGHT_KEY = booleanPreferencesKey("driver_on_right")
private val DASHBOARD_THEME_KEY = stringPreferencesKey("dashboard_theme")
private val MOTOR_DRIVE_TYPE_KEY = stringPreferencesKey("motor_drive_type")

/**
 * Persisted layout and theme preferences for [com.evcompanion.car.screens.DashboardScreen].
 * Remembers which side of the vehicle the driver sits on and which visual theme is active
 * across car drives and app restarts.
 */
class DashboardLayoutConfig(private val context: Context) {
    val driverOnRight: Flow<Boolean> = context.dashboardLayoutDataStore.data.map { it[DRIVER_ON_RIGHT_KEY] ?: false }

    suspend fun setDriverOnRight(onRight: Boolean) {
        context.dashboardLayoutDataStore.edit { it[DRIVER_ON_RIGHT_KEY] = onRight }
    }

    val themeId: Flow<DashboardThemeId> = context.dashboardLayoutDataStore.data.map {
        DashboardThemeId.fromString(it[DASHBOARD_THEME_KEY])
    }

    val activeTheme: Flow<DashboardTheme> = themeId.map { DashboardThemeRegistry.get(it) }

    suspend fun setThemeId(themeId: DashboardThemeId) {
        context.dashboardLayoutDataStore.edit { it[DASHBOARD_THEME_KEY] = themeId.name }
    }

    suspend fun cycleTheme(): DashboardThemeId {
        val current = themeId.first()
        val next = DashboardThemeRegistry.nextTheme(current)
        setThemeId(next)
        return next
    }

    /** Remembers whether this vehicle has been found to be AWD or RWD-only - see MotorDriveTypeTracker. */
    val motorDriveType: Flow<MotorDriveType> = context.dashboardLayoutDataStore.data.map {
        when (it[MOTOR_DRIVE_TYPE_KEY]) {
            MotorDriveType.AWD.name -> MotorDriveType.AWD
            MotorDriveType.RWD_ONLY.name -> MotorDriveType.RWD_ONLY
            else -> MotorDriveType.UNKNOWN
        }
    }

    suspend fun setMotorDriveType(type: MotorDriveType) {
        context.dashboardLayoutDataStore.edit { it[MOTOR_DRIVE_TYPE_KEY] = type.name }
    }
}
