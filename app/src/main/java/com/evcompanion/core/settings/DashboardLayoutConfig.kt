package com.evcompanion.core.settings

import com.evcompanion.core.units.DistanceUnit
import com.evcompanion.core.units.EfficiencyUnit
import com.evcompanion.core.units.PressureUnit
import com.evcompanion.core.units.TemperatureUnit
import com.evcompanion.core.units.UnitPreferences
import com.evcompanion.core.units.UnitSystem
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
private val AUTO_SCAN_DTC_ON_HERO_KEY = booleanPreferencesKey("auto_scan_dtc_on_hero")
private val SOC_FAST_CHARGE_THRESHOLD_KEY = intPreferencesKey("soc_fast_charge_threshold")
private val MIN_FAST_CHARGE_POWER_KW_KEY = intPreferencesKey("min_fast_charge_power_kw")
private val UNIT_SYSTEM_KEY = stringPreferencesKey("unit_system")
private val DISTANCE_UNIT_KEY = stringPreferencesKey("distance_unit")
private val TEMPERATURE_UNIT_KEY = stringPreferencesKey("temperature_unit")
private val PRESSURE_UNIT_KEY = stringPreferencesKey("pressure_unit")
private val EFFICIENCY_UNIT_KEY = stringPreferencesKey("efficiency_unit")

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

    /** Granular unit preferences for distance, temperature, pressure, and efficiency. */
    val unitPreferences: Flow<UnitPreferences> = context.dashboardLayoutDataStore.data.map { prefs ->
        val globalSystem = prefs[UNIT_SYSTEM_KEY]?.let { name ->
            runCatching { UnitSystem.valueOf(name) }.getOrNull()
        } ?: UnitSystem.IMPERIAL
        val defaults = UnitPreferences.fromSystem(globalSystem)

        val dist = prefs[DISTANCE_UNIT_KEY]?.let { name ->
            runCatching { DistanceUnit.valueOf(name) }.getOrNull()
        } ?: defaults.distance

        val temp = prefs[TEMPERATURE_UNIT_KEY]?.let { name ->
            runCatching { TemperatureUnit.valueOf(name) }.getOrNull()
        } ?: defaults.temperature

        val press = prefs[PRESSURE_UNIT_KEY]?.let { name ->
            runCatching { PressureUnit.valueOf(name) }.getOrNull()
        } ?: defaults.pressure

        val eff = prefs[EFFICIENCY_UNIT_KEY]?.let { name ->
            runCatching { EfficiencyUnit.valueOf(name) }.getOrNull()
        } ?: defaults.efficiency

        UnitPreferences(
            distance = dist,
            temperature = temp,
            pressure = press,
            efficiency = eff,
        )
    }

    /** Sets the global system and updates all category defaults accordingly. */
    suspend fun setUnitSystem(system: UnitSystem) {
        val defaults = UnitPreferences.fromSystem(system)
        context.dashboardLayoutDataStore.edit {
            it[UNIT_SYSTEM_KEY] = system.name
            it[DISTANCE_UNIT_KEY] = defaults.distance.name
            it[TEMPERATURE_UNIT_KEY] = defaults.temperature.name
            it[PRESSURE_UNIT_KEY] = defaults.pressure.name
            it[EFFICIENCY_UNIT_KEY] = defaults.efficiency.name
        }
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        context.dashboardLayoutDataStore.edit { it[DISTANCE_UNIT_KEY] = unit.name }
    }

    suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        context.dashboardLayoutDataStore.edit { it[TEMPERATURE_UNIT_KEY] = unit.name }
    }

    suspend fun setPressureUnit(unit: PressureUnit) {
        context.dashboardLayoutDataStore.edit { it[PRESSURE_UNIT_KEY] = unit.name }
    }

    suspend fun setEfficiencyUnit(unit: EfficiencyUnit) {
        context.dashboardLayoutDataStore.edit { it[EFFICIENCY_UNIT_KEY] = unit.name }
    }

    /** Whether to automatically initiate DTC scan when the Diagnostics tile is promoted to hero. */
    val autoScanDtcOnHero: Flow<Boolean> = context.dashboardLayoutDataStore.data.map {
        it[AUTO_SCAN_DTC_ON_HERO_KEY] ?: false
    }

    suspend fun setAutoScanDtcOnHero(enabled: Boolean) {
        context.dashboardLayoutDataStore.edit { it[AUTO_SCAN_DTC_ON_HERO_KEY] = enabled }
    }

    /**
     * Vehicle SoC percentage threshold below which nearby charger discovery filters strictly
     * to high-speed DC fast chargers. Defaults to 30%.
     */
    val socFastChargeThresholdPercent: Flow<Int> = context.dashboardLayoutDataStore.data.map {
        it[SOC_FAST_CHARGE_THRESHOLD_KEY] ?: 30
    }

    suspend fun setSocFastChargeThreshold(percent: Int) {
        context.dashboardLayoutDataStore.edit { it[SOC_FAST_CHARGE_THRESHOLD_KEY] = percent }
    }

    /**
     * Minimum charging power rating (in kW) required when low-SoC fast charge filtering is active.
     * Defaults to 100 kW.
     */
    val minFastChargePowerKw: Flow<Int> = context.dashboardLayoutDataStore.data.map {
        it[MIN_FAST_CHARGE_POWER_KW_KEY] ?: 100
    }

    suspend fun setMinFastChargePowerKw(kw: Int) {
        context.dashboardLayoutDataStore.edit { it[MIN_FAST_CHARGE_POWER_KW_KEY] = kw }
    }
}
