package com.evcompanion.car.screens.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChargingDetectorTest {

    private val stopped = listOf(0.0, 0.0)

    /** Feeds one reading per second from [from] to [to] seconds; returns how many times it fired. */
    private fun ChargingDetector.feed(amps: Double?, rpms: List<Double?>, from: Int, to: Int): Int =
        (from until to).count { update(amps, rpms, it * 1000L) }

    @Test
    fun `fires once after 30s of DC charging, not again while it continues`() {
        val d = ChargingDetector()
        assertEquals(0, d.feed(302.0, stopped, 0, 30))
        assertEquals(1, d.feed(302.0, stopped, 30, 600))
    }

    @Test
    fun `slow AC charge counts`() {
        assertEquals(1, ChargingDetector().feed(2.0, stopped, 0, 60))
    }

    @Test
    fun `regen with a motor turning never fires`() {
        assertEquals(0, ChargingDetector().feed(100.0, listOf(0.0, 3630.0), 0, 120))
    }

    @Test
    fun `parked with small accessory draw never fires`() {
        assertEquals(0, ChargingDetector().feed(-0.6, stopped, 0, 120))
        assertEquals(0, ChargingDetector().feed(0.6, stopped, 0, 120))
    }

    @Test
    fun `missing readings never fire`() {
        assertEquals(0, ChargingDetector().feed(null, stopped, 0, 120))
        assertEquals(0, ChargingDetector().feed(50.0, listOf(null, 0.0), 0, 120))
    }

    @Test
    fun `a brief dropout mid-session does not fire again, a new session does`() {
        val d = ChargingDetector()
        assertEquals(1, d.feed(200.0, stopped, 0, 60))
        assertEquals(0, d.feed(0.0, stopped, 60, 65)) // 5s blip
        assertEquals(0, d.feed(200.0, stopped, 65, 200))
        assertEquals(0, d.feed(0.0, listOf(0.0, 2000.0), 200, 400)) // unplugged, drove off
        assertEquals(1, d.feed(200.0, stopped, 400, 460)) // next charge
        assertFalse(d.update(200.0, stopped, 461_000L))
    }
}
