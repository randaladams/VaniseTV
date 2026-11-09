package com.vanisetv.app.ui

import com.vanisetv.app.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clock and date in the user's chosen style: Brazil 19:45 · 09/11/2026, US 7:45 PM · 11/09/2026. */
object Formats {
    fun locale(lang: String): Locale = if (lang == "pt") Locale("pt", "BR") else Locale.US

    /** Returns (time, amPm) — amPm is empty for Brazil style. */
    fun time(date: Date, style: String): Pair<String, String> =
        if (style == Prefs.STYLE_US) {
            SimpleDateFormat("h:mm", Locale.US).format(date) to SimpleDateFormat("a", Locale.US).format(date)
        } else {
            SimpleDateFormat("HH:mm", Locale.US).format(date) to ""
        }

    fun timeLine(date: Date, style: String): String = time(date, style).let { (t, a) -> if (a.isEmpty()) t else "$t $a" }

    fun date(date: Date, style: String): String =
        SimpleDateFormat(if (style == Prefs.STYLE_US) "MM/dd/yyyy" else "dd/MM/yyyy", Locale.US).format(date)

    fun dayName(date: Date, lang: String): String =
        SimpleDateFormat("EEEE", locale(lang)).format(date).replaceFirstChar { it.titlecase(locale(lang)) }

    fun dateTime(millis: Long, style: String): String = Date(millis).let { "${date(it, style)} ${timeLine(it, style)}" }
}
