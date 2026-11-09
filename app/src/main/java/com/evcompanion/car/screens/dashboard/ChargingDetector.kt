package com.evcompanion.car.screens.dashboard

import kotlin.math.abs

/**
 * Spots a charging session - AC or DC - from the pack itself rather than the charge-port flag
 * bits (which read 0 on a real Ioniq 5 even mid-DC-charge, 2026-11-04 log): current flowing into
 * the pack while both drive motors are stopped. Regen also pushes current in, but only with a
 * motor turning, so it can't trigger this.
 *
 * [update] returns true once per session, after [holdMillis] of steady charging, so the dashboard
 * jumps to the charging page once and then leaves the driver free to page away. It re-arms only
 * after [holdMillis] of not charging, so one dropped reading mid-session doesn't jump again.
 */
class ChargingDetector(
    private val minChargeCurrentAmps: Double = 1.5,
    private val stoppedRpm: Double = 50.0,
    private val holdMillis: Long = 30_000L,
) {
    private var streakStart: Long? = null
    private var streakIsCharging = false
    private var fired = false

    /**
     * [chargeCurrentAmps] is positive into the pack (see VehicleProfile.chargeCurrent for the
     * car's own sign convention); null inputs count as "not charging".
     */
    fun update(chargeCurrentAmps: Double?, motorRpms: List<Double?>, nowMillis: Long): Boolean {
        val charging = chargeCurrentAmps != null && chargeCurrentAmps > minChargeCurrentAmps &&
            motorRpms.isNotEmpty() && motorRpms.all { it != null && abs(it) < stoppedRpm }

        if (streakStart == null || charging != streakIsCharging) {
            streakStart = nowMillis
            streakIsCharging = charging
        }
        val held = nowMillis - (streakStart ?: nowMillis) >= holdMillis
        if (!held) return false

        if (!charging) {
            fired = false
            return false
        }
        if (fired) return false
        fired = true
        return true
    }
}
