package com.vanisetv.app.data

/**
 * One entry of the local channels.m3u file.
 *
 * [extinf] is the original #EXTINF line (minus our own vanise-hidden attribute) and
 * [extraLines] are any option lines (#EXTVLCOPT etc.) between it and the URL, so the
 * file can be written back without losing anything.
 */
data class Channel(
    val name: String,
    val url: String,
    val tvgId: String,
    val logo: String,
    val groups: List<String>,
    val quality: String,
    val geoBlocked: Boolean,
    val not247: Boolean,
    val userAgent: String?,
    val referrer: String?,
    val extinf: String,
    val extraLines: List<String>,
    var hidden: Boolean,
    /** 1 or 2: which channel source (Settings › Channel sources) this entry came from. */
    var source: Int = 1,
) {
    /** Stable identity across refreshes (stream URLs change more often than ids and names). */
    val key: String get() = "$tvgId|$name"

    val mainGroup: String get() = groups.firstOrNull() ?: "Undefined"

    /** Name without accents, capitals or punctuation; computed once, used for search and A–Z sorting. */
    val searchKey: String by lazy { Sources.normalize(name) }

    val isNovela: Boolean
        get() = name.contains("novela", ignoreCase = true) ||
            groups.any { it.contains("novela", ignoreCase = true) }
}

/** A row of the category chip in Live TV. [id] is "all", "novelas" or a group name. */
data class Category(val id: String, val label: String, val channels: List<Channel>)
