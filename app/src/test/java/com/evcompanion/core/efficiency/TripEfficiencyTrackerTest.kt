package com.evcompanion.core.efficiency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEfficiencyTrackerTest {

    @Test
    fun `update returns null average when insufficient distance or energy`() {
        val tracker = TripEfficiencyTracker()
        val snap = tracker.update(
            speedMph = 60.0,
            batteryPowerKw = 15.0,
            cumDischargedKwh = 100.0,
            cumChargedKwh = 10.0,
            odometerMiles = 1000.0,
        )
        // First sample seeds baseline, delta is 0
        assertNull(snap.tripAverageMiPerKwh)
        assertEquals(0.0, snap.tripDistanceMiles!!, 0.001)
        assertEquals(0.0, snap.tripNetKwh!!, 0.001)
    }

    @Test
    fun `update computes accurate trip average efficiency`() {
        val tracker = TripEfficiencyTracker()
        // Baseline
        tracker.update(60.0, 15.0, 100.0, 10.0, 1000.0)

        // Driven 10 miles, discharged 3.5 kWh, regen charged 0.5 kWh -> net 3.0 kWh
        val snap = tracker.update(
            speedMph = 60.0,
            batteryPowerKw = 15.0,
            cumDischargedKwh = 103.5,
            cumChargedKwh = 10.5,
            odometerMiles = 1010.0,
        )

        assertEquals(10.0, snap.tripDistanceMiles!!, 0.001)
        assertEquals(3.0, snap.tripNetKwh!!, 0.001)
        // 10 miles / 3.0 kWh = 3.333 mi/kWh
        assertNotNull(snap.tripAverageMiPerKwh)
        assertEquals(3.333, snap.tripAverageMiPerKwh!!, 0.01)
    }

    @Test
    fun `instant efficiency detects regen`() {
        val tracker = TripEfficiencyTracker()
        val snap = tracker.update(
            speedMph = 45.0,
            batteryPowerKw = -25.0, // Regen
            cumDischargedKwh = 100.0,
            cumChargedKwh = 10.0,
            odometerMiles = 1000.0,
        )
        assertTrue(snap.isRegen)
        assertNull(snap.instantMiPerKwh)
    }

    @Test
    fun `instant efficiency computes speed over power when cruising`() {
        val tracker = TripEfficiencyTracker(emaAlpha = 1.0) // raw
        val snap = tracker.update(
            speedMph = 60.0,
            batteryPowerKw = 15.0,
            cumDischargedKwh = 100.0,
            cumChargedKwh = 10.0,
            odometerMiles = 1000.0,
        )
        // 60 / 15 = 4.0 mi/kWh
        assertEquals(4.0, snap.instantMiPerKwh!!, 0.01)
    }

    @Test
    fun `energy charged at a standstill is left out of the trip`() {
        val tracker = TripEfficiencyTracker()
        tracker.update(60.0, 15.0, 100.0, 10.0, 1000.0)
        // Drive 10 mi using 3 kWh, then stop and DC fast charge 50 kWh, then drive 10 more mi using 3 kWh.
        tracker.update(60.0, 15.0, 103.0, 10.0, 1010.0)
        tracker.update(0.0, -120.0, 103.0, 35.0, 1010.0)
        tracker.update(0.0, -120.0, 103.0, 60.0, 1010.0)
        val snap = tracker.update(60.0, 15.0, 106.0, 60.0, 1020.0)

        assertEquals(6.0, snap.tripNetKwh!!, 0.001)
        assertEquals(20.0 / 6.0, snap.tripAverageMiPerKwh!!, 0.001)
    }
}
