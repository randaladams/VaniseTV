package com.vanisetv.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.vanisetv.app.App
import com.vanisetv.app.data.Channel

/**
 * One ExoPlayer for the whole Live TV screen, with protection against fast channel changes:
 *
 *  - a channel request only starts after the remote has been still for [SETTLE_MS];
 *    later requests replace earlier ones, so only the last channel loads;
 *  - the previous stream is fully stopped before anything new starts;
 *  - a stream that errors or buffers for more than [STALL_MS] is retried once, then reported
 *    as unavailable instead of leaving a frozen picture.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val callback: Callback,
    /** Channel Editor preview: no sound, and it doesn't count toward "most watched". */
    private val silent: Boolean = false,
) {

    interface Callback {
        fun onTuning(channel: Channel)
        fun onPlaying(channel: Channel)
        fun onFailed(channel: Channel)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /** Channel whose stream is loaded (it may still be buffering). */
    var current: Channel? = null; private set
    /** Channel waiting for the remote to settle. */
    var pending: Channel? = null; private set
    val isTuning: Boolean get() = pending != null
    val isActive: Boolean get() = current != null || pending != null

    private var retried = false
    private var playingSince = 0L
    private var watchedMs = 0L

    fun create() {
        if (player != null) return
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_000, 4_000)
            .build()
        player = ExoPlayer.Builder(context).setLoadControl(loadControl).build().also {
            it.addListener(listener)
            if (silent) it.volume = 0f
            view.player = it
        }
    }

    /** Stops the preview now (and any channel waiting to start). */
    fun stop() {
        handler.removeCallbacks(startRunnable)
        pending = null
        stopCurrent()
    }

    fun release() {
        flushStats()
        handler.removeCallbacksAndMessages(null)
        pending = null
        view.player = null
        player?.release()
        player = null
        current = null
    }

    /** Ask for a channel. Repeated calls within the settle time only change the target. */
    fun request(channel: Channel, settleMs: Long = SETTLE_MS) {
        handler.removeCallbacks(startRunnable)
        if (current != null && current?.key != channel.key) stopCurrent()
        pending = channel
        callback.onTuning(channel)
        handler.postDelayed(startRunnable, settleMs)
    }

    private val startRunnable = Runnable {
        val ch = pending ?: return@Runnable
        pending = null
        start(ch)
    }

    // ---------------- subtitles & audio ----------------

    /** One selectable audio or subtitle track of the current stream. */
    data class TrackOption(val group: Tracks.Group, val index: Int, val format: Format, val selected: Boolean) {
        val language: String? get() = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    }

    /** Supported tracks of [type] (C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT) in the playing stream. */
    fun options(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> TrackOption(g, i, g.getTrackFormat(i), g.isTrackSelected(i)) }
        }
    }

    /** Switches to [option] right away (no restart). */
    fun select(option: TrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.group.type, false)
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    fun subtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** The remembered choices (Prefs) decide the tracks each new channel starts with. */
    private fun applyPreferences(p: ExoPlayer) {
        val prefs = App.instance.prefs
        val b = p.trackSelectionParameters.buildUpon().clearOverrides()
            .setPreferredAudioLanguage(prefs.audioLang.ifEmpty { null })
        when (val s = prefs.subsLang) {
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "und" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(true)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(s).setSelectUndeterminedTextLanguage(false)
        }
        p.trackSelectionParameters = b.build()
    }

    private fun start(channel: Channel) {
        stopCurrent()
        val p = player ?: return
        applyPreferences(p)
        current = channel
        retried = false
        p.setMediaSource(buildSource(channel))
        p.prepare()
        p.playWhenReady = true
        armWatchdog()
    }

    private fun stopCurrent() {
        flushStats()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(retryRunnable)
        player?.run { stop(); clearMediaItems() }
        current = null
    }

    private fun buildSource(channel: Channel) = DefaultMediaSourceFactory(
        DefaultHttpDataSource.Factory()
            .setUserAgent(channel.userAgent ?: Util.getUserAgent(context, "VaniseTV"))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .apply { channel.referrer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) } }
    ).createMediaSource(
        MediaItem.Builder().setUri(channel.url).apply {
            if (channel.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
    )

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val ch = current ?: return
            when (state) {
                Player.STATE_READY -> { handler.removeCallbacks(watchdog); callback.onPlaying(ch) }
                Player.STATE_BUFFERING -> armWatchdog()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) playingSince = SystemClock.elapsedRealtime()
            else if (playingSince > 0) { watchedMs += SystemClock.elapsedRealtime() - playingSince; playingSince = 0 }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Common with live HLS after a hiccup: jump back to the live edge.
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            retryOrFail()
        }
    }

    private val watchdog = Runnable { retryOrFail() }
    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, STALL_MS)
    }

    private val retryRunnable = Runnable {
        val ch = current ?: return@Runnable
        val p = player ?: return@Runnable
        p.setMediaSource(buildSource(ch))
        p.prepare()
        p.playWhenReady = true
        armWatchdog()
    }

    private fun retryOrFail() {
        val ch = current ?: return
        handler.removeCallbacks(watchdog)
        if (!retried) {
            retried = true
            player?.stop()
            handler.postDelayed(retryRunnable, 2_000)
        } else {
            stopCurrent()
            callback.onFailed(ch)
        }
    }

    /** Records the viewing time of the current channel (only sessions of 30 s or more count). */
    private fun flushStats() {
        val ch = current ?: return
        if (playingSince > 0) { watchedMs += SystemClock.elapsedRealtime() - playingSince; playingSince = SystemClock.elapsedRealtime() }
        if (watchedMs > 0 && !silent) App.instance.stats.addSession(ch.key, watchedMs / 1000)
        watchedMs = 0
        if (player?.isPlaying != true) playingSince = 0
    }

    companion object {
        const val SETTLE_MS = 900L
        const val ZAP_SETTLE_MS = 700L
        const val STALL_MS = 15_000L
    }
}
