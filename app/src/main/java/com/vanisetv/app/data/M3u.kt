package com.vanisetv.app.data

/** Reads and writes the M3U playlist format used by iptv-org. */
object M3u {
    private const val HIDDEN_ATTR = "vanise-hidden"
    private const val SOURCE_ATTR = "vanise-source"
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val qualityRegex = Regex("""\s*\((\d{3,4}[pi])\)""")
    private val tagRegex = Regex("""\s*\[([^\]]+)\]""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var extinf: String? = null
        val extra = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> { extinf = line; extra.clear() }
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#") -> if (extinf != null) extra.add(line)
                else -> {
                    val info = extinf ?: continue
                    build(info, extra.toList(), line)?.let(out::add)
                    extinf = null
                    extra.clear()
                }
            }
        }
        return out
    }

    private fun build(extinfLine: String, extra: List<String>, url: String): Channel? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val attrs = HashMap<String, String>()
        attrRegex.findAll(extinfLine).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }

        // Title is everything after the first comma that follows the last quoted attribute.
        val lastQuote = extinfLine.lastIndexOf('"')
        val comma = extinfLine.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        var title = if (comma >= 0) extinfLine.substring(comma + 1).trim() else ""

        val quality = qualityRegex.find(title)?.groupValues?.get(1) ?: ""
        val tags = tagRegex.findAll(title).map { it.groupValues[1] }.toList()
        title = title.replace(qualityRegex, "").replace(tagRegex, "").trim()
        if (title.isEmpty()) title = attrs["tvg-name"] ?: url

        var ua: String? = attrs["http-user-agent"]
        var ref: String? = attrs["http-referrer"]
        for (e in extra) {
            val v = e.substringAfter(':', "")
            when {
                v.startsWith("http-user-agent=", true) -> ua = v.substringAfter('=')
                v.startsWith("http-referrer=", true) -> ref = v.substringAfter('=')
            }
        }

        val groups = (attrs["group-title"] ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return Channel(
            name = title,
            url = url,
            tvgId = attrs["tvg-id"] ?: "",
            logo = attrs["tvg-logo"] ?: "",
            groups = groups.ifEmpty { listOf("Undefined") },
            quality = quality,
            geoBlocked = tags.any { it.contains("geo", true) },
            not247 = tags.any { it.contains("24/7", true) },
            userAgent = ua?.takeIf { it.isNotBlank() },
            referrer = ref?.takeIf { it.isNotBlank() },
            extinf = stripOwnAttrs(extinfLine),
            extraLines = extra,
            hidden = attrs[HIDDEN_ATTR] == "1",
            source = attrs[SOURCE_ATTR]?.toIntOrNull() ?: 1,
        )
    }

    private fun stripOwnAttrs(line: String) = line.replace(Regex("""\s+(?:$HIDDEN_ATTR|$SOURCE_ATTR)="[^"]*""""), "")

    fun write(channels: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        for (c in channels) {
            // Our attributes go right after "#EXTINF:-1"
            val own = " $SOURCE_ATTR=\"${c.source}\"" + if (c.hidden) " $HIDDEN_ATTR=\"1\"" else ""
            val m = Regex("""^#EXTINF:\s*-?\d+""", RegexOption.IGNORE_CASE).find(c.extinf)
            val info = if (m != null) c.extinf.substring(0, m.range.last + 1) + own + c.extinf.substring(m.range.last + 1)
            else c.extinf
            append(info).append('\n')
            c.extraLines.forEach { append(it).append('\n') }
            append(c.url).append('\n')
        }
    }
}
