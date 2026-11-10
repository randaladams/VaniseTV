package com.vanisetv.app.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Quick scan / Full scan: checks whether each channel's stream answers.
 * A channel counts as dead when it doesn't answer within [timeoutSeconds] or answers with an
 * error (4xx/5xx). Only the response headers are read; the stream itself isn't downloaded.
 */
object StreamChecker {
    const val PARALLEL = 8
    private val PLAYER_UA = "VaniseTV (Linux;Android ${Build.VERSION.RELEASE}) AndroidXMedia3/1.4.1"

    /**
     * Returns the dead channels, in list order. [onProgress] is called on the main thread with
     * (checked so far, dead so far). Cancel the calling coroutine to stop early.
     */
    suspend fun findDead(
        channels: List<Channel>,
        timeoutSeconds: Long,
        onProgress: (checked: Int, dead: Int) -> Unit,
    ): List<Channel> = coroutineScope {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val gate = Semaphore(PARALLEL)
        val checked = AtomicInteger()
        val deadCount = AtomicInteger()
        val results = channels.map { ch ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val alive = runInterruptible { isAlive(client, ch) }
                    if (!alive) deadCount.incrementAndGet()
                    val n = checked.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(n, deadCount.get()) }
                    ch to alive
                }
            }
        }.awaitAll()
        client.dispatcher.executorService.shutdown()
        results.filter { !it.second }.map { it.first }
    }

    private fun isAlive(client: OkHttpClient, ch: Channel): Boolean = try {
        val req = Request.Builder().url(ch.url)
            .header("User-Agent", ch.userAgent ?: PLAYER_UA)
            .apply { ch.referrer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}
