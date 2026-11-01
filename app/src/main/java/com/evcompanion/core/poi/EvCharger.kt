package com.evcompanion.core.poi

/**
 * Represents an EV charging station for POI discovery and navigation hand-off.
 */
data class EvCharger(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val maxPowerKw: Int,
    val plugCount: Int = 4,
    val network: String? = null,
    val distanceMiles: Double? = null
)
