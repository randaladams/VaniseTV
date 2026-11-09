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

        (tiles + liveTile).forEach { it.scaleOnFocus() }
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
        logo.findViewById<TextView>(R.id.logoText).apply { text = "LIVE"; visibility = View.VISIBLE }
        liveTile.findViewById<TextView>(R.id.tileTitle).setText(R.string.live_tv)
        liveTile.findViewById<TextView>(R.id.tileSub).text = getString(R.string.live_sub, visible.size)
        liveTile.findViewById<TextView>(R.id.tileRank).text = ""

        if (currentFocus == null || currentFocus?.id == R.id.btnRetry) (if (top.isNotEmpty()) tiles[0] else liveTile).requestFocus()
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

    private fun showBackground() {
        if (backgroundShown) return
        val photo = Backgrounds.random(this) ?: return
        backgroundShown = true
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
        dlg.content(getString(R.string.refresh_title), getString(R.string.refresh_body))
            .buttons(getString(R.string.refresh_now), {
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
            }, getString(R.string.cancel), { dlg.dismiss() }, focusSecondary = true)
            .show()
    }
}
