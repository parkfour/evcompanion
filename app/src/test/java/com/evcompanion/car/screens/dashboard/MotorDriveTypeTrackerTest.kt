package com.evcompanion.car.screens.dashboard

import com.evcompanion.core.settings.MotorDriveType
import org.junit.Assert.assertEquals
import org.junit.Test

class MotorDriveTypeTrackerTest {

    @Test
    fun `a non-zero front reading immediately sets AWD`() {
        val tracker = MotorDriveTypeTracker()

        val result = tracker.update(frontValue = 120.0, nowMillis = 1_000L)

        assertEquals(MotorDriveType.AWD, result)
    }

    @Test
    fun `a zero front reading for less than the threshold stays UNKNOWN`() {
        val tracker = MotorDriveTypeTracker(zeroStreakThresholdMillis = 30_000L)

        tracker.update(frontValue = 0.0, nowMillis = 0L)
        val result = tracker.update(frontValue = 0.0, nowMillis = 29_000L)

        assertEquals(MotorDriveType.UNKNOWN, result)
    }

    @Test
    fun `a zero front reading for the full threshold latches RWD_ONLY`() {
        val tracker = MotorDriveTypeTracker(zeroStreakThresholdMillis = 30_000L)

        tracker.update(frontValue = 0.0, nowMillis = 0L)
        val result = tracker.update(frontValue = 0.0, nowMillis = 30_000L)

        assertEquals(MotorDriveType.RWD_ONLY, result)
    }

    @Test
    fun `once AWD is reached a later zero streak never reverts to RWD_ONLY`() {
        val tracker = MotorDriveTypeTracker(zeroStreakThresholdMillis = 30_000L)

        tracker.update(frontValue = 50.0, nowMillis = 0L)
        val result = tracker.update(frontValue = 0.0, nowMillis = 60_000L)

        assertEquals(MotorDriveType.AWD, result)
    }

    @Test
    fun `RWD_ONLY reverts to AWD immediately on a non-zero front reading`() {
        val tracker = MotorDriveTypeTracker(zeroStreakThresholdMillis = 30_000L)
        tracker.update(frontValue = 0.0, nowMillis = 0L)
        tracker.update(frontValue = 0.0, nowMillis = 30_000L)

        val result = tracker.update(frontValue = 5.0, nowMillis = 45_000L)

        assertEquals(MotorDriveType.AWD, result)
    }

    @Test
    fun `seed sets the state without requiring a streak`() {
        val tracker = MotorDriveTypeTracker()

        tracker.seed(MotorDriveType.RWD_ONLY)

        assertEquals(MotorDriveType.RWD_ONLY, tracker.driveType)
    }
}
