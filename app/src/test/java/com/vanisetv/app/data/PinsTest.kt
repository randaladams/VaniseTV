package com.vanisetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinsTest {
    @Test fun newestPinGoesFirst() {
        val (list, dropped) = Pins.add(listOf("b", "c"), "a")
        assertEquals(listOf("a", "b", "c"), list)
        assertNull(dropped)
    }

    @Test fun sixthPinDropsTheOldest() {
        val (list, dropped) = Pins.add(listOf("e", "d", "c", "b", "a"), "f")
        assertEquals(listOf("f", "e", "d", "c", "b"), list)
        assertEquals("a", dropped)
    }

    @Test fun repinningMovesToFrontWithoutDropping() {
        val (list, dropped) = Pins.add(listOf("e", "d", "c", "b", "a"), "c")
        assertEquals(listOf("c", "e", "d", "b", "a"), list)
        assertNull(dropped)
    }

    @Test fun unpinRemovesOnlyThatChannel() {
        assertEquals(listOf("e", "c"), Pins.remove(listOf("e", "d", "c"), "d"))
    }
}
