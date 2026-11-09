package com.vanisetv.app.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Defaults: Brazil time style, English, click sounds on, 10 minute screen saver. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var style: String
        get() = sp.getString("style", STYLE_BR) ?: STYLE_BR
        set(v) = sp.edit().putString("style", v).apply()

    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) = sp.edit().putString("lang", v).apply()

    var clicks: Boolean
        get() = sp.getBoolean("clicks", true)
        set(v) = sp.edit().putBoolean("clicks", v).apply()

    /** 0 = never. */
    var saverMinutes: Int
        get() = sp.getInt("saver", 10)
        set(v) = sp.edit().putInt("saver", v).apply()

    var lastDownload: Long
        get() = sp.getLong("lastDownload", 0L)
        set(v) = sp.edit().putLong("lastDownload", v).apply()

    /** Channel source addresses; an empty string means the slot is not used. */
    var source1: String
        get() = sp.getString("source1", Sources.DEFAULT_1) ?: Sources.DEFAULT_1
        set(v) = sp.edit().putString("source1", v.trim()).apply()

    var source2: String
        get() = sp.getString("source2", Sources.DEFAULT_2) ?: Sources.DEFAULT_2
        set(v) = sp.edit().putString("source2", v.trim()).apply()

    /** Result of the last download: "fromSource1|newFromSource2|duplicates|skippedYoutube". */
    var lastSourceStats: String
        get() = sp.getString("srcStats", "") ?: ""
        set(v) = sp.edit().putString("srcStats", v).apply()

    var lastBackgroundFetch: Long
        get() = sp.getLong("lastBg", 0L)
        set(v) = sp.edit().putLong("lastBg", v).apply()

    companion object {
        const val STYLE_BR = "br"
        const val STYLE_US = "us"
    }
}
