package com.vanisetv.app.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Seconds watched per channel per day, kept for the last 28 days.
 * A viewing session only counts once it reaches 30 seconds.
 */
class WatchStats(context: Context) {
    private val sp = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    @Synchronized
    fun addSession(channelKey: String, seconds: Long) {
        if (seconds < MIN_SESSION_SECONDS) return
        val root = load()
        val today = today()
        val days = root.optJSONObject(channelKey) ?: JSONObject().also { root.put(channelKey, it) }
        days.put(today.toString(), days.optLong(today.toString()) + seconds)
        prune(root, today)
        sp.edit().putString("data", root.toString()).apply()
    }

    /** Channel keys with their total seconds in the window, most watched first. */
    @Synchronized
    fun ranking(): List<Pair<String, Long>> {
        val root = load()
        val from = today() - WINDOW_DAYS + 1
        val out = ArrayList<Pair<String, Long>>()
        root.keys().forEach { key ->
            val days = root.optJSONObject(key) ?: return@forEach
            var total = 0L
            days.keys().forEach { d -> if ((d.toLongOrNull() ?: 0) >= from) total += days.optLong(d) }
            if (total > 0) out += key to total
        }
        return out.sortedByDescending { it.second }
    }

    private fun load(): JSONObject = try {
        JSONObject(sp.getString("data", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun prune(root: JSONObject, today: Long) {
        val from = today - WINDOW_DAYS + 1
        val emptyKeys = ArrayList<String>()
        root.keys().forEach { key ->
            val days = root.optJSONObject(key) ?: return@forEach
            val old = ArrayList<String>()
            days.keys().forEach { d -> if ((d.toLongOrNull() ?: 0) < from) old += d }
            old.forEach(days::remove)
            if (days.length() == 0) emptyKeys += key
        }
        emptyKeys.forEach(root::remove)
    }

    private fun today(): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() + offset)
    }

    companion object {
        const val WINDOW_DAYS = 28
        const val MIN_SESSION_SECONDS = 30L
    }
}
