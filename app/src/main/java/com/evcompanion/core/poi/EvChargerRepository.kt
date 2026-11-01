package com.evcompanion.core.poi

import android.content.Context
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
import java.net.URLEncoder
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

    private fun fetchLiveChargers(lat: Double, lon: Double) {
        scope.launch(Dispatchers.IO) {
            try {
                val query = """[out:json][timeout:4];node["amenity"="charging_station"](around:35000,$lat,$lon);out body 15;"""
                val urlString = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "EVCompanion/0.12 (Android Auto)")
                }

                try {
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    _nearbyChargers.value = parseOverpassJson(response, lat, lon)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // Forget the query point so the next location update retries.
                lastQueriedLat = null
                lastQueriedLon = null
                DebugFileLogger.w(tag, "Overpass live query failed (${e.message}), keeping previous chargers")
            }
        }
    }

    companion object {
        internal fun parseOverpassJson(jsonString: String, userLat: Double, userLon: Double): List<EvCharger> {
            val list = mutableListOf<EvCharger>()
            try {
                val root = JSONObject(jsonString)
                val elements = root.optJSONArray("elements") ?: return emptyList()
                for (i in 0 until elements.length()) {
                    val elem = elements.getJSONObject(i)
                    val id = elem.optString("id", "osm_$i")
                    val lat = elem.optDouble("lat", userLat)
                    val lon = elem.optDouble("lon", userLon)
                    val tags = elem.optJSONObject("tags")

                    // optString returns "" for a missing key, so blank -> null before any ?: fallback.
                    fun tag(key: String): String? = tags?.optString(key)?.takeIf { it.isNotBlank() }
                    var name = tag("name")
                    val operator = tag("operator") ?: tag("brand")
                    val street = tag("addr:street")
                    val city = tag("addr:city")

                    val address = when {
                        !street.isNullOrBlank() && !city.isNullOrBlank() -> "$street, $city"
                        !street.isNullOrBlank() -> street
                        !city.isNullOrBlank() -> city
                        else -> ""
                    }

                    // 0 = unknown: shown as "?kW" and excluded from the low-SoC fast filter, rather than
                    // guessing from the brand (most ChargePoint/Tesla destination sites are Level 2).
                    val maxPower = listOf("socket:type2_combo:output", "socket:tesla_supercharger:output", "charging_station:output", "max_power")
                        .firstNotNullOfOrNull { key -> tag(key)?.let(::parseKw) } ?: 0

                    if (name.isNullOrBlank()) {
                        name = operator ?: "EV Charger"
                    }

                    val capacity = tag("capacity")?.toIntOrNull() ?: 0
                    val dist = calculateDistanceMiles(userLat, userLon, lat, lon)

                    list.add(
                        EvCharger(
                            id = id,
                            name = name,
                            address = address,
                            latitude = lat,
                            longitude = lon,
                            maxPowerKw = maxPower,
                            plugCount = capacity,
                            network = operator,
                            distanceMiles = dist
                        )
                    )
                }
            } catch (e: Exception) {
                DebugFileLogger.w("EvChargerRepo", "JSON parse error: ${e.message}")
            }
            return list.sortedBy { it.distanceMiles ?: Double.MAX_VALUE }
        }

        /** "150 kW", "150", "22 kW;50 kW" (takes the max) -> kW, or null when unparseable/0. */
        internal fun parseKw(raw: String): Int? =
            Regex("""(\d+(?:\.\d+)?)""").findAll(raw).mapNotNull { it.value.toDoubleOrNull() }
                .maxOrNull()?.toInt()?.takeIf { it > 0 }

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
