package com.vanisetv.app.data

/** Pinned channels on the home screen: newest first, at most [MAX]; adding a sixth drops the oldest. */
object Pins {
    const val MAX = 5

    /** Returns the new list and the key that fell off (or null). Re-pinning moves a channel to the front. */
    fun add(pins: List<String>, key: String): Pair<List<String>, String?> {
        val list = listOf(key) + pins.filter { it != key }
        return if (list.size > MAX) list.take(MAX) to list.last() else list to null
    }

    fun remove(pins: List<String>, key: String): List<String> = pins.filter { it != key }
}
