package com.vanisetv.app

import android.app.Application
import android.media.AudioAttributes
import android.media.SoundPool
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import com.vanisetv.app.data.Backgrounds
import com.vanisetv.app.data.ChannelRepository
import com.vanisetv.app.data.Net
import com.vanisetv.app.data.Prefs
import com.vanisetv.app.data.WatchStats

class App : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs; private set
    lateinit var repo: ChannelRepository; private set
    lateinit var stats: WatchStats; private set

    private lateinit var soundPool: SoundPool
    private var clickId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = ChannelRepository(this, prefs)
        stats = WatchStats(this)
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        clickId = soundPool.load(this, R.raw.click, 1)
        try { Backgrounds.schedule(this) } catch (_: Exception) { }
    }

    fun playClick() {
        if (prefs.clicks && clickId != 0) soundPool.play(clickId, 0.6f, 0.6f, 1, 0, 1f)
    }

    /** Channel logos: cached on disk so each one is downloaded only once. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.client }
        .components { add(SvgDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(40L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: App; private set
    }
}
