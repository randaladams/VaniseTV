package com.vanisetv.app.data

import org.json.JSONArray
import java.io.IOException
import java.text.Normalizer

/**
 * The two channel sources from Settings › Channel sources. Either slot accepts:
 *  - an M3U playlist link (e.g. https://iptv-org.github.io/iptv/countries/br.m3u), or
 *  - a famelack country page (e.g. https://famelack.com/tv/br) or its JSON data file.
 * The format is detected from what is downloaded, not from which slot it is in.
 */
object Sources {
    const val DEFAULT_1 = "https://iptv-org.github.io/iptv/countries/br.m3u"
    const val DEFAULT_2 = "https://famelack.com/tv/br"

    private val famelackPage = Regex("""famelack\.com/(?:tv/)?([a-z]{2})(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

    /** famelack.com/tv/br is a web page; its channels live in a JSON file on GitHub. */
    fun resolve(url: String): String {
        val u = url.trim()
        famelackPage.find(u)?.let {
            return "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/raw/countries/${it.groupValues[1].lowercase()}.json"
        }
        return u
    }

    data class Fetched(val channels: List<Channel>, val skippedYoutube: Int)

    /** Blocking download + parse of one source. */
    fun fetch(url: String): Fetched = parse(Net.getText(resolve(url)))

    fun parse(text: String): Fetched {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val result = if (t.startsWith("[")) parseFamelack(t) else Fetched(M3u.parse(t), 0)
        if (result.channels.isEmpty()) throw IOException("No playable channels found")
        return result
    }

    /** famelack JSON → channels. Entries with only YouTube links are skipped (the player can't play them). */
    fun parseFamelack(json: String): Fetched {
        val arr = JSONArray(json)
        val m3u = StringBuilder("#EXTM3U\n")
        var youtube = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val streams = o.optJSONObject("sources")?.optJSONArray("streams")
            val url = streams?.optString(0).orEmpty()
            if (url.isBlank()) { youtube++; continue }
            val name = o.optString("name").replace('"', '\'').replace('\n', ' ').trim()
            if (name.isEmpty()) continue
            val geo = if (o.optBoolean("isGeoBlocked")) " [Geo-blocked]" else ""
            m3u.append("#EXTINF:-1 tvg-id=\"\" tvg-logo=\"\" group-title=\"Undefined\",").append(name).append(geo).append('\n')
            m3u.append(url.trim()).append('\n')
        }
        return Fetched(M3u.parse(m3u.toString()), youtube)
    }

    data class Merged(
        val channels: List<Channel>,
        val fromSource1: Int,
        val newFromSource2: Int,
        val duplicates: Int,
        val skippedYoutube: Int,
    )

    /**
     * Combines the sources in order. A later channel is a duplicate when its stream URL or
     * its name (ignoring accents, capitals, spaces and punctuation) was already seen; the
     * earlier copy is kept. Each channel is marked with the source number it came from.
     */
    fun merge(sources: List<Fetched?>): Merged {
        val out = ArrayList<Channel>()
        val urls = HashSet<String>()
        val names = HashSet<String>()
        val counts = IntArray(sources.size)
        var dups = 0
        var youtube = 0
        sources.forEachIndexed { i, f ->
            if (f == null) return@forEachIndexed
            youtube += f.skippedYoutube
            val firstUrls = HashSet<String>()
            val firstNames = HashSet<String>()
            for (c in f.channels) {
                val n = normalize(c.name)
                if (urls.contains(c.url) || names.contains(n)) { dups++; continue }
                c.source = i + 1
                out += c
                counts[i]++
                firstUrls += c.url
                firstNames += n
            }
            // Duplicates are only checked against earlier sources, not within a source.
            urls += firstUrls
            names += firstNames
        }
        return Merged(out, counts.getOrElse(0) { 0 }, counts.getOrElse(1) { 0 }, dups, youtube)
    }

    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
}
