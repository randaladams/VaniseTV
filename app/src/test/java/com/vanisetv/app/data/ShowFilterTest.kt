package com.vanisetv.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowFilterTest {
    @Test fun allListsEveryChannel() {
        assertTrue(ShowFilter.ALL.matches(hidden = false))
        assertTrue(ShowFilter.ALL.matches(hidden = true))
    }

    @Test fun shownAndHiddenListOnlyTheirOwn() {
        assertTrue(ShowFilter.SHOWN.matches(hidden = false))
        assertFalse(ShowFilter.SHOWN.matches(hidden = true))
        assertTrue(ShowFilter.HIDDEN.matches(hidden = true))
        assertFalse(ShowFilter.HIDDEN.matches(hidden = false))
    }

    @Test fun aChannelJustSwitchedStaysListed() {
        assertTrue(ShowFilter.SHOWN.matches(hidden = true, switched = true))
        assertTrue(ShowFilter.HIDDEN.matches(hidden = false, switched = true))
    }
}
