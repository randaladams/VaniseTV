package com.vanisetv.app.data

/** Which channels the Channel Editor lists. */
enum class ShowFilter {
    ALL, SHOWN, HIDDEN;

    /**
     * [switched] marks a channel changed while this filter is on: it stays listed, even though
     * it no longer matches, so a wrong press can be undone.
     */
    fun matches(hidden: Boolean, switched: Boolean = false): Boolean = when (this) {
        ALL -> true
        SHOWN -> switched || !hidden
        HIDDEN -> switched || hidden
    }
}
