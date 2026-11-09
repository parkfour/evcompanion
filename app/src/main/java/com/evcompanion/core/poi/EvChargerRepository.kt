package com.evcompanion.core.poi

import android.content.Context
import com.evcompanion.BuildConfig
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.location.VehicleLocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of smart low-SoC filtering on nearby chargers. */
data class ChargerFilterResult(
    val chargers: List<EvCharger>,
    val isSmartFilterActive: Boolean,
    val title: String,
    val subtitle: String? = null
)

class EvChargerRepository(
    private val context: Context,
    private val locationProvider: VehicleLocationProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val tag = "EvChargerRepo"

    private val _nearbyChargers = MutableStateFlow<List<EvCharger>>(emptyList())
    val nearbyChargers: StateFlow<List<EvCharger>> = _nearbyChargers.asStateFlow()

    private var lastQueriedLat: Double? = null
    private var lastQueriedLon: Double? = null

    init {
        scope.launch {
            locationProvider.currentLocation.collectLatest { loc ->
                // No location yet -> empty list ("Searching..."); never show made-up stations.
                if (loc != null) updateChargersForLocation(loc.latitude, loc.longitude)
            }
        }
    }

    private fun updateChargersForLocation(lat: Double, lon: Double) {
        // If moved more than ~3km or not queried yet, trigger background query
        val lastLat = lastQueriedLat
        val lastLon = lastQueriedLon
        val distanceMoved = if (lastLat != null && lastLon != null) {
            calculateDistanceMiles(lastLat, lastLon, lat, lon)
        } else {
            Double.MAX_VALUE
        }

        if (distanceMoved > 2.0) {
            lastQueriedLat = lat
            lastQueriedLon = lon
            fetchLiveChargers(lat, lon)
        }
    }

    // After a failed lookup, wait before trying again (1 min, doubling to 15) instead of retrying
    // on every location update - the old behaviour hammered the server 566 times in one drive.
    private var failures = 0
    private var retryAfterMillis = 0L

    private fun fetchLiveChargers(lat: Double, lon: Double) {
        val apiKey = BuildConfig.OCM_API_KEY
        if (apiKey.isBlank()) {
            DebugFileLogger.w(tag, "No Open Charge Map key in this build (OCM_API_KEY) - charger lookup disabled")
            return
        }
        if (System.currentTimeMillis() < retryAfterMillis) {
            lastQueriedLat = null // retry on a later location update once the wait is over
            lastQueriedLon = null
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.openchargemap.io/v3/poi/?output=json&compact=false&verbose=false" +
                        "&latitude=$lat&longitude=$lon&distance=$SEARCH_RADIUS_KM&distanceunit=KM&maxresults=$MAX_RESULTS",
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("X-API-Key", apiKey)
                    setRequestProperty("User-Agent", "EVCompanion/${BuildConfig.VERSION_NAME} (Android Auto)")
                }
                try {
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val chargers = parseOpenChargeMapJson(response, lat, lon)
                    _nearbyChargers.value = chargers
                    failures = 0
                    DebugFileLogger.i(tag, "Open Charge Map: ${chargers.size} chargers within ${SEARCH_RADIUS_KM}km")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastQueriedLat = null
                lastQueriedLon = null
                failures++
                val waitMillis = (60_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(15 * 60_000L)
                retryAfterMillis = System.currentTimeMillis() + waitMillis
                DebugFileLogger.w(tag, "Charger lookup failed (${e.message}), keeping previous chargers, retry in ${waitMillis / 1000}s")
            }
        }
    }

    companion object {
        private const val SEARCH_RADIUS_KM = 40
        private const val MAX_RESULTS = 60 // enough to still find fast chargers when the low-SoC filter kicks in

        /** Open Charge Map /v3/poi response -> chargers, nearest first. Malformed entries are skipped. */
        internal fun parseOpenChargeMapJson(jsonString: String, userLat: Double, userLon: Double): List<EvCharger> {
            val list = mutableListOf<EvCharger>()
            try {
                val pois = org.json.JSONArray(jsonString)
                for (i in 0 until pois.length()) {
                    val poi = pois.optJSONObject(i) ?: continue
                    val info = poi.optJSONObject("AddressInfo") ?: continue
                    if (!info.has("Latitude") || !info.has("Longitude")) continue
                    val lat = info.optDouble("Latitude")
                    val lon = info.optDouble("Longitude")
                    fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

                    val operator = poi.optJSONObject("OperatorInfo")?.text("Title")
                    val connections = poi.optJSONArray("Connections")
                    var maxPower = 0.0
                    var plugs = 0
                    for (c in 0 until (connections?.length() ?: 0)) {
                        val conn = connections?.optJSONObject(c) ?: continue
                        maxPower = maxOf(maxPower, conn.optDouble("PowerKW", 0.0).takeUnless { it.isNaN() } ?: 0.0)
                        plugs += conn.optInt("Quantity", 1).coerceAtLeast(1)
                    }
                    val address = listOfNotNull(info.text("AddressLine1"), info.text("Town")).joinToString(", ")

                    list.add(
                        EvCharger(
                            id = poi.optString("ID", "ocm_$i"),
                            name = info.text("Title") ?: operator ?: "EV Charger",
                            address = address,
                            latitude = lat,
                            longitude = lon,
                            // 0 = unknown: shown as "?kW" and excluded from the low-SoC fast filter.
                            maxPowerKw = maxPower.toInt(),
                            plugCount = poi.optInt("NumberOfPoints", 0).takeIf { it > 0 } ?: plugs,
                            network = operator,
                            distanceMiles = calculateDistanceMiles(userLat, userLon, lat, lon),
                        ),
                    )
                }
            } catch (e: Exception) {
                DebugFileLogger.w("EvChargerRepo", "JSON parse error: ${e.message}")
            }
            return list.sortedBy { it.distanceMiles ?: Double.MAX_VALUE }
        }

        /**
         * Calculates great-circle distance between two points in miles using pure Kotlin Haversine.
         */
        fun calculateDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusMiles = 3958.8
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (earthRadiusMiles * c)
        }

        /**
         * Filters charging stations according to battery SoC and driver preferences.
         *
         * When SoC drops below [socThresholdPercent] (default 30%), filters strictly to fast
         * chargers with power >= [minPowerKw] (default 100 kW) and marks [isSmartFilterActive] true.
         */
        fun filterChargers(
            chargers: List<EvCharger>,
            currentSocPercent: Double?,
            socThresholdPercent: Int = 30,
            minPowerKw: Int = 100,
            limit: Int = 4
        ): ChargerFilterResult {
            val isLowBattery = currentSocPercent != null && currentSocPercent < socThresholdPercent
            return if (isLowBattery) {
                val filtered = chargers
                    .filter { it.maxPowerKw >= minPowerKw }
                    .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }
                    .take(limit)

                ChargerFilterResult(
                    chargers = filtered,
                    isSmartFilterActive = true,
                    title = "DC FAST CHARGERS (≥$minPowerKw kW)",
                    subtitle = "Low SoC (<$socThresholdPercent%) Auto-Filter"
                )
            } else {
                val sorted = chargers
                    .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }
                    .take(limit)

                ChargerFilterResult(
                    chargers = sorted,
                    isSmartFilterActive = false,
                    title = "NEARBY CHARGERS",
                    subtitle = null
                )
            }
        }
    }
}
