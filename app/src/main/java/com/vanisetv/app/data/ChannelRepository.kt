package com.vanisetv.app.data

import android.content.Context
import com.vanisetv.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Owns the local copy of the playlist (files/channels.m3u). Everything works from this
 * copy until the user picks Refresh Channels. Hidden flags are stored inside the file.
 */
class ChannelRepository(private val context: Context, private val prefs: Prefs) {
    private val file = File(context.filesDir, "channels.m3u")
    private val writeLock = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var channels: List<Channel> = emptyList()
        private set

    val isLoaded: Boolean get() = channels.isNotEmpty()
    val visible: List<Channel> get() = channels.filter { !it.hidden }

    /** Loads the local file, downloading it first on the very first start. */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        if (file.exists()) {
            channels = M3u.parse(file.readText())
            if (channels.isNotEmpty()) return@withContext
        }
        val merged = download()
        val fresh = merged.channels
        fresh.forEach { it.hidden = it.geoBlocked }
        save(fresh)
        channels = fresh
        prefs.lastDownload = System.currentTimeMillis()
    }

    data class RefreshResult(val merged: Sources.Merged, val hidden: Int)

    /** Downloads both sources again, combines them and keeps the user's hidden choices. */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        val merged = download()
        val fresh = merged.channels
        val oldByKey = channels.associateBy { it.key }
        val oldByUrl = channels.associateBy { it.url }
        for (c in fresh) {
            val old = oldByKey[c.key] ?: oldByUrl[c.url]
            c.hidden = old?.hidden ?: c.geoBlocked
        }
        save(fresh)
        channels = fresh
        prefs.lastDownload = System.currentTimeMillis()
        RefreshResult(merged, fresh.count { it.hidden })
    }

    fun setHidden(channel: Channel, hidden: Boolean) {
        channel.hidden = hidden
        val snapshot = channels
        ioScope.launch { save(snapshot) }
    }

    fun find(key: String?): Channel? = channels.firstOrNull { it.key == key }

    /** "All", "Novelas" (when present), then every group by size. Only visible channels. */
    fun categories(): List<Category> {
        val vis = visible
        val out = ArrayList<Category>()
        out += Category("all", context.getString(R.string.all_channels), vis)
        val novelas = vis.filter { it.isNovela }
        if (novelas.isNotEmpty()) out += Category("novelas", context.getString(R.string.cat_novelas), novelas)
        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        vis.forEach { c -> c.groups.forEach { g -> byGroup.getOrPut(g) { ArrayList() }.add(c) } }
        byGroup.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<Channel>>> { it.key.equals("Undefined", true) }.thenByDescending { it.value.size })
            .forEach { out += Category(it.key, groupLabel(it.key), it.value) }
        return out
    }

    fun groupLabel(group: String): String {
        val pt = prefs.lang == "pt"
        val map = if (pt) GROUPS_PT else GROUPS_EN
        return map[group.lowercase()] ?: group
    }

    /**
     * Downloads every configured source. If any configured source fails, the whole download
     * fails and the current local list is kept (so a broken source never wipes channels).
     */
    private fun download(): Sources.Merged {
        val urls = listOf(prefs.source1, prefs.source2)
        if (urls.all { it.isBlank() }) throw SourceException(0, IOException("No channel sources configured"))
        val fetched = urls.mapIndexed { i, url ->
            if (url.isBlank()) null
            else try { Sources.fetch(url) } catch (e: Exception) { throw SourceException(i + 1, e) }
        }
        val merged = Sources.merge(fetched)
        if (merged.channels.isEmpty()) throw IOException("No channels found")
        prefs.lastSourceStats = "${merged.fromSource1}|${merged.newFromSource2}|${merged.duplicates}|${merged.skippedYoutube}"
        return merged
    }

    /** [source] is 1 or 2 (0 when nothing is configured). */
    class SourceException(val source: Int, cause: Exception) : IOException("Source $source failed", cause)

    private suspend fun save(list: List<Channel>) = writeLock.withLock {
        val tmp = File(file.parentFile, "channels.m3u.tmp")
        tmp.writeText(M3u.write(list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private val GROUPS_EN = mapOf("undefined" to "Other")
        private val GROUPS_PT = mapOf(
            "undefined" to "Outros", "general" to "Geral", "news" to "Notícias",
            "entertainment" to "Entretenimento", "movies" to "Filmes", "series" to "Séries",
            "kids" to "Infantil", "religious" to "Religioso", "education" to "Educação",
            "sports" to "Esportes", "music" to "Música", "documentary" to "Documentários",
            "legislative" to "Legislativo", "animation" to "Animação", "outdoor" to "Ar livre",
            "shop" to "Compras", "lifestyle" to "Estilo de vida", "culture" to "Cultura",
            "business" to "Negócios", "comedy" to "Comédia", "cooking" to "Culinária",
            "family" to "Família", "classic" to "Clássicos", "auto" to "Automóveis",
            "travel" to "Viagem", "show" to "Programas", "top-news" to "Destaques", "weather" to "Clima", "science" to "Ciência", "relax" to "Relaxar",
        )
    }
}
