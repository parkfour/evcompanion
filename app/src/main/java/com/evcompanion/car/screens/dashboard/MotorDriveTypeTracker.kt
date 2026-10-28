package com.evcompanion.car.screens.dashboard

import com.evcompanion.core.settings.MotorDriveType

/**
 * Auto-detects whether this specific vehicle actually has a working front drive motor, for the
 * Motor RPM tile: some Ioniq 5 trims are RWD-only and their front-motor PID always reads ~0.
 *
 * A single non-zero front reading means the car is AWD - permanently. A real AWD car can
 * legitimately coast with its front motor at 0rpm for extended periods (regen, single-motor-only
 * driving modes) without that meaning it's actually RWD-only, so once AWD is confirmed this never
 * reverts. Only a car that never once shows a non-zero front reading, for a continuous
 * [zeroStreakThresholdMillis], is assumed RWD-only - and even then, a single later non-zero
 * reading immediately (and permanently) reverts to AWD.
 */
class MotorDriveTypeTracker(
    initial: MotorDriveType = MotorDriveType.UNKNOWN,
    private val zeroStreakThresholdMillis: Long = 30_000L,
) {
    var driveType: MotorDriveType = initial
        private set

    private var zeroStreakStartMillis: Long? = null

    /** Seeds the already-known state (e.g. loaded from persistence) without going through [update]'s transition logic. */
    fun seed(type: MotorDriveType) {
        driveType = type
    }

    /** Call on every poll tick with the front motor's latest raw value (or null if unavailable). */
    fun update(frontValue: Double?, nowMillis: Long): MotorDriveType {
        if (driveType == MotorDriveType.AWD) return driveType

        if (frontValue != null && frontValue > 0.0) {
            driveType = MotorDriveType.AWD
            zeroStreakStartMillis = null
            return driveType
        }

        val streakStart = zeroStreakStartMillis ?: nowMillis.also { zeroStreakStartMillis = it }
        if (nowMillis - streakStart >= zeroStreakThresholdMillis) {
            driveType = MotorDriveType.RWD_ONLY
        }
        return driveType
    }
}
