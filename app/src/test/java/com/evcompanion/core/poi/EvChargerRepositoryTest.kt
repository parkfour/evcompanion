package com.evcompanion.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvChargerRepositoryTest {

    @Test
    fun testHaversineDistanceCalculation() {
        // Same point -> 0 miles
        val d0 = EvChargerRepository.calculateDistanceMiles(33.7490, -84.3880, 33.7490, -84.3880)
        assertEquals(0.0, d0, 0.001)

        // Atlanta to New York City (~745 miles)
        val dAtlNyc = EvChargerRepository.calculateDistanceMiles(33.7490, -84.3880, 40.7128, -74.0060)
        assertTrue("Distance should be approx 745 miles, was $dAtlNyc", dAtlNyc in 730.0..760.0)

        // Local displacement (~0.02 deg lat is approx 1.38 miles)
        val dLocal = EvChargerRepository.calculateDistanceMiles(33.7490, -84.3880, 33.7690, -84.3880)
        assertTrue("Local distance should be ~1.38 miles, was $dLocal", dLocal in 1.3..1.5)
    }

    @Test
    fun testFilterChargers_normalSoC_showsAllSortedByDistance() {
        val chargers = listOf(
            EvCharger("1", "Level 2 Charger", "City Hall", 33.75, -84.38, maxPowerKw = 11, distanceMiles = 1.2),
            EvCharger("2", "50 kW DC Fast", "Market", 33.76, -84.37, maxPowerKw = 50, distanceMiles = 2.5),
            EvCharger("3", "150 kW EVgo", "Mall", 33.77, -84.36, maxPowerKw = 150, distanceMiles = 3.1),
            EvCharger("4", "350 kW Electrify America", "Highway Plaza", 33.78, -84.35, maxPowerKw = 350, distanceMiles = 4.0),
        )

        // SoC is 65% (above default 30% threshold)
        val result = EvChargerRepository.filterChargers(
            chargers = chargers,
            currentSocPercent = 65.0,
            socThresholdPercent = 30,
            minPowerKw = 100,
            limit = 4
        )

        assertFalse(result.isSmartFilterActive)
        assertEquals("NEARBY CHARGERS", result.title)
        assertEquals(4, result.chargers.size)
        // Verified sorted by distance ascending
        assertEquals("1", result.chargers[0].id)
        assertEquals("2", result.chargers[1].id)
        assertEquals("3", result.chargers[2].id)
        assertEquals("4", result.chargers[3].id)
    }

    @Test
    fun testFilterChargers_lowSoC_filtersToFastChargersOnly() {
        val chargers = listOf(
            EvCharger("1", "Level 2 Charger", "City Hall", 33.75, -84.38, maxPowerKw = 11, distanceMiles = 0.5),
            EvCharger("2", "50 kW DC Fast", "Market", 33.76, -84.37, maxPowerKw = 50, distanceMiles = 1.0),
            EvCharger("3", "150 kW EVgo", "Mall", 33.77, -84.36, maxPowerKw = 150, distanceMiles = 2.2),
            EvCharger("4", "350 kW Electrify America", "Highway Plaza", 33.78, -84.35, maxPowerKw = 350, distanceMiles = 3.5),
        )

        // Battery is at 24% (< 30% default threshold)
        val result = EvChargerRepository.filterChargers(
            chargers = chargers,
            currentSocPercent = 24.0,
            socThresholdPercent = 30,
            minPowerKw = 100,
            limit = 4
        )

        assertTrue(result.isSmartFilterActive)
        assertEquals("DC FAST CHARGERS (≥100 kW)", result.title)
        assertEquals("Low SoC (<30%) Auto-Filter", result.subtitle)

        // 11 kW and 50 kW should be excluded! Only 150 kW and 350 kW remain
        assertEquals(2, result.chargers.size)
        assertEquals("3", result.chargers[0].id)
        assertEquals("4", result.chargers[1].id)
    }

    @Test
    fun testFilterChargers_customThresholdAndKw() {
        val chargers = listOf(
            EvCharger("1", "100 kW Station", "Depot", 33.75, -84.38, maxPowerKw = 100, distanceMiles = 1.0),
            EvCharger("2", "150 kW EVgo", "Plaza", 33.76, -84.37, maxPowerKw = 150, distanceMiles = 2.0),
            EvCharger("3", "250 kW Supercharger", "Target", 33.77, -84.36, maxPowerKw = 250, distanceMiles = 3.0),
        )

        // Custom settings: threshold 45%, minPower 150 kW. Vehicle SoC is 40%.
        val result = EvChargerRepository.filterChargers(
            chargers = chargers,
            currentSocPercent = 40.0,
            socThresholdPercent = 45,
            minPowerKw = 150,
            limit = 4
        )

        assertTrue(result.isSmartFilterActive)
        assertEquals("DC FAST CHARGERS (≥150 kW)", result.title)
        assertEquals("Low SoC (<45%) Auto-Filter", result.subtitle)

        // 100 kW station should be excluded
        assertEquals(2, result.chargers.size)
        assertEquals("2", result.chargers[0].id)
        assertEquals("3", result.chargers[1].id)
    }

    @Test
    fun testFilterChargers_nullSoc_behavesAsNormal() {
        val chargers = listOf(
            EvCharger("1", "Level 2", "Library", 33.75, -84.38, maxPowerKw = 11, distanceMiles = 1.0),
            EvCharger("2", "150 kW", "Mall", 33.76, -84.37, maxPowerKw = 150, distanceMiles = 2.0),
        )

        val result = EvChargerRepository.filterChargers(
            chargers = chargers,
            currentSocPercent = null,
            socThresholdPercent = 30,
            minPowerKw = 100,
            limit = 4
        )

        assertFalse(result.isSmartFilterActive)
        assertEquals("NEARBY CHARGERS", result.title)
        assertEquals(2, result.chargers.size)
    }

    @Test
    fun parseOverpassJson_missingTagsFallBackAndUnknownPowerIsZero() {
        val json = """{"elements":[
            {"id":1,"lat":33.75,"lon":-84.38,"tags":{"brand":"EVgo","socket:type2_combo:output":"","charging_station:output":"22 kW;150 kW"}},
            {"id":2,"lat":33.76,"lon":-84.38,"tags":{"amenity":"charging_station"}}
        ]}"""

        val parsed = EvChargerRepository.parseOverpassJson(json, 33.75, -84.38)

        val evgo = parsed.first { it.id == "1" }
        assertEquals("EVgo", evgo.name)       // no name -> brand (operator missing)
        assertEquals("EVgo", evgo.network)
        assertEquals(150, evgo.maxPowerKw)    // blank first key skipped, max of "22 kW;150 kW"
        val bare = parsed.first { it.id == "2" }
        assertEquals("EV Charger", bare.name)
        assertEquals(0, bare.maxPowerKw)      // unknown, not guessed
        assertEquals(null, bare.network)
        assertTrue(EvChargerRepository.filterChargers(parsed, 10.0, 30, 100).chargers.none { it.id == "2" })
    }
}
