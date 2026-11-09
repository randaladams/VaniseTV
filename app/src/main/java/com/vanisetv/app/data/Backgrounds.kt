package com.vanisetv.app.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Home screen backgrounds from Wikimedia Commons. Once a week a few random landscape photos
 * are downloaded at screen size; at most [MAX_STORED] are kept on the device.
 */
object Backgrounds {
    const val MAX_STORED = 10
    private const val PER_WEEK = 3
    private const val CATEGORY = "Category:Quality_images_of_Brazil"
    private const val API = "https://commons.wikimedia.org/w/api.php"

    data class Photo(val file: File, val artist: String)

    private fun dir(context: Context) = File(context.filesDir, "backgrounds").apply { mkdirs() }

    fun list(context: Context): List<Photo> =
        dir(context).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
            .sortedByDescending { it.name }
            .map { f ->
                val meta = File(f.path.removeSuffix(".jpg") + ".json")
                val artist = try { JSONObject(meta.readText()).optString("artist") } catch (e: Exception) { "" }
                Photo(f, artist)
            }

    fun random(context: Context): Photo? = list(context).randomOrNull()

    @Volatile private var current: Photo? = null

    /** The photo in use right now (home screen and screen saver share it). Picked once per app start. */
    fun current(context: Context): Photo? {
        current?.takeIf { it.file.exists() }?.let { return it }
        return random(context).also { current = it }
    }

    /** Moves on to the next stored photo (the screen saver does this every few minutes). */
    fun next(context: Context): Photo? {
        val all = list(context)
        if (all.isEmpty()) return null
        val i = all.indexOfFirst { it.file == current?.file }
        return all[(i + 1) % all.size].also { current = it }
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "backgrounds-weekly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackgroundWorker>(7, TimeUnit.DAYS).setConstraints(constraints).build()
        )
        if (list(context).isEmpty()) {
            wm.enqueueUniqueWork(
                "backgrounds-first", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackgroundWorker>().setConstraints(constraints).build()
            )
        }
    }

    /** Downloads up to [PER_WEEK] new photos and trims the folder to [MAX_STORED]. */
    suspend fun fetch(context: Context): Int = withContext(Dispatchers.IO) {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val members = JSONObject(
            Net.getText("$API?action=query&format=json&list=categorymembers&cmtype=file&cmlimit=500&cmtitle=${enc(CATEGORY)}")
        ).getJSONObject("query").getJSONArray("categorymembers")
        val titles = (0 until members.length()).map { members.getJSONObject(it).getString("title") }
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
            .shuffled().take(12)
        if (titles.isEmpty()) return@withContext 0

        val info = JSONObject(
            Net.getText(
                "$API?action=query&format=json&prop=imageinfo&iiprop=url|size|extmetadata" +
                    "&iiextmetadatafilter=Artist&iiurlwidth=1920&titles=${enc(titles.joinToString("|"))}"
            )
        ).getJSONObject("query").getJSONObject("pages")

        val folder = dir(context)
        val existing = folder.list().orEmpty().toSet()
        var saved = 0
        for (k in info.keys()) {
            if (saved >= PER_WEEK) break
            val ii = info.getJSONObject(k).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = ii.optInt("width"); val h = ii.optInt("height")
            if (w < 1600 || w < h * 1.3) continue   // landscape, TV-sized photos only
            val thumb = ii.optString("thumburl")
            if (thumb.isEmpty()) continue
            val id = Integer.toHexString(thumb.hashCode())
            if (existing.any { it.contains(id) }) continue
            val bytes = try { Net.getBytes(thumb) } catch (e: Exception) { continue }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) continue
            val artistHtml = ii.optJSONObject("extmetadata")?.optJSONObject("Artist")?.optString("value") ?: ""
            val artist = artistHtml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim().take(60)
            val base = "${System.currentTimeMillis()}_$id"
            File(folder, "$base.jpg").writeBytes(bytes)
            File(folder, "$base.json").writeText(JSONObject().put("artist", artist).put("title", k).toString())
            saved++
        }
        // Keep the newest MAX_STORED
        folder.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
            .sortedByDescending { it.name }.drop(MAX_STORED)
            .forEach { it.delete(); File(it.path.removeSuffix(".jpg") + ".json").delete() }
        saved
    }
}

class BackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Backgrounds.fetch(applicationContext)
        Prefs(applicationContext).lastBackgroundFetch = System.currentTimeMillis()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
