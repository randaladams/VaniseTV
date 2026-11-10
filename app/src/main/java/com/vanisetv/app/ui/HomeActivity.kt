package com.vanisetv.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import coil.load
import com.vanisetv.app.R
import com.vanisetv.app.data.Backgrounds
import com.vanisetv.app.data.Channel
import com.vanisetv.app.data.ChannelRepository
import com.vanisetv.app.data.StreamChecker
import kotlinx.coroutines.launch
import java.util.Date

class HomeActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var date: TextView
    private lateinit var day: TextView
    private lateinit var tiles: List<View>
    private lateinit var liveTile: View
    private lateinit var pinTiles: List<View>
    private var backgroundShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        clock = findViewById(R.id.clock)
        ampm = findViewById(R.id.ampm)
        date = findViewById(R.id.date)
        day = findViewById(R.id.day)
        tiles = listOf(findViewById(R.id.tile1), findViewById(R.id.tile2), findViewById(R.id.tile3))
        liveTile = findViewById(R.id.tileLive)
        pinTiles = listOf(R.id.pin1, R.id.pin2, R.id.pin3, R.id.pin4, R.id.pin5).map { findViewById(it) }

        (tiles + liveTile + pinTiles).forEach { it.scaleOnFocus() }
        listOf(R.id.btnSettings, R.id.btnEditor, R.id.btnRefresh).forEach { findViewById<View>(it).scaleOnFocus(1.03f) }

        liveTile.setBackgroundResource(R.drawable.bg_tile_live)
        liveTile.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnEditor).setOnClickListener { startActivity(Intent(this, EditorActivity::class.java)) }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { confirmRefresh() }
        findViewById<View>(R.id.btnRetry).setOnClickListener { load() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        if (app.repo.isLoaded) renderTiles()
        showBackground()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onSaverHidden() = showBackground()

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            val (t, a) = Formats.time(now, app.prefs.style)
            clock.text = t
            ampm.text = a
            ampm.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
            date.text = Formats.date(now, app.prefs.style)
            day.text = Formats.dayName(now, app.prefs.lang)
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    // ---------------- loading ----------------

    private fun load() {
        val overlay = findViewById<View>(R.id.loading)
        val text = findViewById<TextView>(R.id.loadingText)
        val spinner = findViewById<View>(R.id.loadingSpinner)
        val retry = findViewById<View>(R.id.btnRetry)
        if (app.repo.isLoaded) { overlay.visibility = View.GONE; renderTiles(); return }

        overlay.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        retry.visibility = View.GONE
        text.setText(R.string.loading_channels)
        lifecycleScope.launch {
            try {
                app.repo.ensureLoaded()
                overlay.visibility = View.GONE
                renderTiles()
                liveTile.requestFocus()
            } catch (e: Exception) {
                spinner.visibility = View.GONE
                text.setText(R.string.load_failed)
                retry.visibility = View.VISIBLE
                retry.requestFocus()
            }
        }
    }

    // ---------------- tiles ----------------

    private fun renderTiles() {
        val visible = app.repo.visible
        val byKey = visible.associateBy { it.key }
        val top = app.stats.ranking().mapNotNull { (k, s) -> byKey[k]?.let { it to s } }.take(3)

        tiles.forEachIndexed { i, tile ->
            val entry = top.getOrNull(i)
            bindTile(tile, entry?.first, entry?.second, i + 1)
        }

        val logo = liveTile.findViewById<View>(R.id.tileLogo)
        bindLogo(logo, null)
        logo.setBackgroundResource(R.drawable.bg_live_badge)
        logo.findViewById<TextView>(R.id.logoText).apply { setText(R.string.live_badge); textSize = 11f; maxLines = 1; visibility = View.VISIBLE }
        liveTile.findViewById<TextView>(R.id.tileTitle).setText(R.string.live_tv)
        liveTile.findViewById<TextView>(R.id.tileSub).text = getString(R.string.live_sub, visible.size)
        liveTile.findViewById<TextView>(R.id.tileRank).text = ""

        val pinned = renderPins(byKey)
        if (currentFocus == null || currentFocus?.id == R.id.btnRetry) {
            (if (pinned > 0) pinTiles[0] else if (top.isNotEmpty()) tiles[0] else liveTile).requestFocus()
        }
    }

    /** Pinned row: newest first; empty slots explain how to pin. Returns how many slots are filled. */
    private fun renderPins(byKey: Map<String, Channel>): Int {
        val pinned = app.prefs.pins.mapNotNull { byKey[it] }   // hidden or removed channels are skipped
        pinTiles.forEachIndexed { i, tile ->
            val channel = pinned.getOrNull(i)
            val logo = tile.findViewById<View>(R.id.pinLogo)
            val name = tile.findViewById<TextView>(R.id.pinName)
            if (channel == null) {
                bindLogo(logo, null)
                logo.visibility = View.GONE
                tile.setBackgroundResource(R.drawable.bg_pin_empty)
                name.setText(R.string.pin_empty)
                name.textSize = 11f
                name.setTextColor(getColor(R.color.muted))
                name.gravity = android.view.Gravity.CENTER
                tile.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
            } else {
                logo.visibility = View.VISIBLE
                bindLogo(logo, channel)
                tile.setBackgroundResource(R.drawable.bg_tile)
                name.text = channel.name
                name.textSize = 15f
                name.setTextColor(getColor(R.color.fg))
                name.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                tile.setOnClickListener {
                    startActivity(Intent(this, GuideActivity::class.java)
                        .putExtra(GuideActivity.EXTRA_CHANNEL, channel.key)
                        .putExtra(GuideActivity.EXTRA_FULLSCREEN, true))
                }
            }
        }
        return pinned.size
    }

    private fun bindTile(tile: View, channel: Channel?, seconds: Long?, rank: Int) {
        val title = tile.findViewById<TextView>(R.id.tileTitle)
        val sub = tile.findViewById<TextView>(R.id.tileSub)
        val logo = tile.findViewById<View>(R.id.tileLogo)
        tile.findViewById<TextView>(R.id.tileRank).text = "#$rank"
        if (channel == null) {
            bindLogo(logo, null)
            logo.visibility = View.GONE
            title.text = ""
            sub.setText(R.string.empty_tile)
            tile.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
            return
        }
        logo.visibility = View.VISIBLE
        bindLogo(logo, channel)
        title.text = channel.name
        sub.text = getString(R.string.watched, formatWatched(this, seconds ?: 0))
        // Most-watched tiles go straight to full screen.
        tile.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java)
                .putExtra(GuideActivity.EXTRA_CHANNEL, channel.key)
                .putExtra(GuideActivity.EXTRA_FULLSCREEN, true))
        }
    }

    private var backgroundFile: java.io.File? = null

    private fun showBackground() {
        val photo = Backgrounds.current(this) ?: return
        // The screen saver may have moved on to another photo; follow it.
        if (backgroundShown && photo.file == backgroundFile) return
        backgroundShown = true
        backgroundFile = photo.file
        findViewById<ImageView>(R.id.background).load(photo.file) { crossfade(600) }
        findViewById<TextView>(R.id.credit).text =
            if (photo.artist.isNotBlank()) getString(R.string.photo_credit, photo.artist) else "Wikimedia Commons"
    }

    // ---------------- dialogs ----------------

    private fun confirmExit() {
        val dlg = TvDialog(this)
        dlg.onBack = { finish(); true }    // Back again exits
        dlg.content(getString(R.string.exit_title), getString(R.string.exit_body))
            .buttons(getString(R.string.exit), { dlg.dismiss(); finish() }, getString(R.string.cancel), { dlg.dismiss() }, focusSecondary = true)
            .show()
    }

    private fun confirmRefresh() {
        val dlg = TvDialog(this)
        dlg.content(getString(R.string.refresh_title), getString(R.string.refresh_body) + "\n\n" + getString(R.string.scan_info))
            .choices(0,
                getString(R.string.refresh_now) to { refreshNow(dlg) },
                getString(R.string.scan_quick) to { runScan(dlg, 3) },
                getString(R.string.scan_full) to { runScan(dlg, 5) },
                getString(R.string.cancel) to { dlg.dismiss() })
            .show()
    }

    /** Quick scan (3 s) / Full scan (5 s): hides every shown channel that doesn't answer in time. */
    private fun runScan(dlg: TvDialog, seconds: Long) {
        val list = app.repo.visible
        dlg.content(getString(R.string.scan_title, seconds.toInt()), getString(R.string.scan_progress, 0, list.size, 0))
        dlg.progress(0, list.size)
        val job = lifecycleScope.launch {
            val dead = StreamChecker.findDead(list, seconds) { checked, deadSoFar ->
                dlg.setBody(getString(R.string.scan_progress, checked, list.size, deadSoFar))
                dlg.progress(checked, list.size)
            }
            app.repo.setHidden(dead, true)
            dlg.onBack = null
            if (dead.isEmpty()) {
                dlg.content(getString(R.string.scan_done_title), getString(R.string.scan_none, list.size, seconds.toInt()))
            } else {
                val shown = dead.take(SCAN_LIST_MAX).joinToString("\n") { "• ${it.name}" }
                val more = if (dead.size > SCAN_LIST_MAX) "\n" + getString(R.string.scan_more, dead.size - SCAN_LIST_MAX) else ""
                dlg.content(getString(R.string.scan_done_title),
                    getString(R.string.scan_done, dead.size, list.size, seconds.toInt()) + "\n\n" + shown + more + "\n\n" + getString(R.string.scan_foot))
            }
            dlg.buttons(getString(R.string.ok), { dlg.dismiss() })
            renderTiles()
        }
        // Back stops the scan; nothing is hidden unless it finishes.
        dlg.onBack = {
            job.cancel()
            dlg.content(getString(R.string.refresh_channels), getString(R.string.scan_stopped))
            dlg.buttons(getString(R.string.ok), { dlg.dismiss() })
            dlg.onBack = null
            true
        }
    }

    private fun refreshNow(dlg: TvDialog) {
                dlg.content(getString(R.string.loading_channels), "").busy()
                dlg.content(getString(R.string.loading_channels), "").busy()
                dlg.onBack = { true }   // can't cancel mid-download
                lifecycleScope.launch {
                    try {
                        val r = app.repo.refresh()
                        val m = r.merged
                        dlg.content(getString(R.string.refresh_done_title), getString(R.string.refresh_done,
                            m.channels.size, m.fromSource1, m.newFromSource2, m.duplicates, m.skippedYoutube, r.hidden))
                    } catch (e: ChannelRepository.SourceException) {
                        dlg.content(getString(R.string.refresh_channels),
                            if (e.source > 0) getString(R.string.refresh_failed_source, e.source) else getString(R.string.refresh_failed))
                    } catch (e: Exception) {
                        dlg.content(getString(R.string.refresh_channels), getString(R.string.refresh_failed))
                    }
                    dlg.onBack = null
                    dlg.buttons(getString(R.string.ok), { dlg.dismiss() })
                    renderTiles()
                }
    }

    companion object {
        /** Scan results list at most this many names; the rest are counted. */
        private const val SCAN_LIST_MAX = 6
    }
}
