package com.evcompanion.car.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardGridTest {

    @Test
    fun promoting_tile_swaps_it_with_the_big_value() {
        val order = (0..7).toMutableList() // 8 tiles: [0] is the big value
        promoteAt(order, slot = 3)     // tap the 4th secondary tile (catalog 4)
        assertEquals(4, order[0])
        assertEquals(0, order[4])         // old big returns to the tapped slot
        assertEquals(listOf(1, 2, 3, 0, 5, 6, 7), order.drop(1))
    }

    @Test
    fun promoting_last_tile_works() {
        val order = (0..7).toMutableList()
        promoteAt(order, slot = 6)
        assertEquals(7, order[0])
        assertEquals(0, order.last())
    }

    @Test
    fun tap_outside_row_slots_is_a_no_op() {
        val order = (0..7).toMutableList()
        promoteAt(order, slot = 7)     // beyond the 7 secondary slots
        assertEquals((0..7).toList(), order)
    }

    @Test
    fun smaller_page_promotes_within_its_size() {
        val order = mutableListOf(0, 1, 2) // 1 big + 2 secondary
        promoteAt(order, slot = 1)
        assertEquals(2, order[0])
        assertEquals(0, order[2])
    }
}