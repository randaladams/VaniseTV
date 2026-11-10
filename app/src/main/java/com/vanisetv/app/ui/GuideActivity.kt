package com.vanisetv.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanisetv.app.R
import com.vanisetv.app.data.Category
import com.vanisetv.app.data.Channel
import com.vanisetv.app.data.Pins
import com.vanisetv.app.data.Sources
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(UnstableApi::class)
class GuideActivity : BaseActivity(), TvPlayer.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: TvPlayer
    private lateinit var list: RecyclerView
    private lateinit var chip: TextView
    private lateinit var videoBox: FrameLayout
    private lateinit var videoMsgBox: View
    private lateinit var videoMsg: TextView
    private lateinit var videoSpinner: View
    private lateinit var banner: View
    private lateinit var status: TextView

    private var categories: List<Category> = emptyList()
    private var catIndex = 0
    private val adapter = ChannelAdapter()
    private var ready = false
    private var fullscreen = false
    private var launchedFullscreen = false
    private var resumeChannel: Channel? = null
    private lateinit var previewParams: FrameLayout.LayoutParams

    override val isPlaybackActive: Boolean get() = ::player.isInitialized && player.isActive

    private lateinit var search: EditText
    private lateinit var letterView: TextView
    private var query = ""
    /** Non-null while a search is active: matches from all visible channels, A–Z. */
    private var searchResults: List<Channel>? = null

    private val channels: List<Channel> get() = searchResults ?: categories.getOrNull(catIndex)?.channels.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        list = findViewById(R.id.list)
        chip = findViewById(R.id.category)
        videoBox = findViewById(R.id.videoBox)
        videoMsgBox = findViewById(R.id.videoMsgBox)
        videoMsg = findViewById(R.id.videoMsg)
        videoSpinner = findViewById(R.id.videoSpinner)
        banner = findViewById(R.id.banner)
        status = findViewById(R.id.status)
        previewParams = FrameLayout.LayoutParams(videoBox.layoutParams as FrameLayout.LayoutParams)

        player = TvPlayer(this, findViewById<PlayerView>(R.id.playerView), this)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter

        chip.setOnClickListener { setCategory(catIndex + 1) }
        chip.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { setCategory(catIndex + 1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { setCategory(catIndex - 1); true }
                else -> false
            }
        }
        chip.setOnFocusChangeListener { _, has -> if (has) showInfo(player.pending ?: player.current) }

        search = findViewById(R.id.search)
        letterView = findViewById(R.id.letter)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!ready) return
                handler.removeCallbacks(runSearch)
                if (s.isNullOrBlank()) applySearch("")          // clearing is immediate
                else {
                    // Wait until typing pauses so each key press stays fast.
                    status.setText(R.string.search_wait)
                    handler.postDelayed(runSearch, SEARCH_DELAY_MS)
                }
            }
        })
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(runSearch)
                applySearch(search.text.toString())                // Search key: go now
                hideKeyboard()
                if (channels.isNotEmpty()) focusRow(0)
                true
            } else false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen && !launchedFullscreen -> exitFullscreen()
                    fullscreen -> finish()
                    search.text.isNotEmpty() -> { search.setText(""); search.requestFocus() }   // first Back clears the search
                    else -> finish()
                }
            }
        })

        videoMsg.setText(R.string.nothing_playing)
        status.setText(R.string.press_ok)

        lifecycleScope.launch {
            try { app.repo.ensureLoaded() } catch (e: Exception) { finish(); return@launch }
            setup()
        }
    }

    private fun setup() {
        categories = app.repo.categories()
        player.create()
        val start = app.repo.find(intent.getStringExtra(EXTRA_CHANNEL))
        setCategory(0)
        ready = true
        if (start != null) {
            player.request(start, 0)
            if (intent.getBooleanExtra(EXTRA_FULLSCREEN, false)) {
                launchedFullscreen = true
                enterFullscreen()
            } else focusChannel(start)
        } else {
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: chip.requestFocus() }
        }
    }

    override fun onStart() {
        super.onStart()
        player.create()
        resumeChannel?.let { player.request(it, 0) }
        resumeChannel = null
    }

    override fun onStop() {
        super.onStop()
        // Free the decoder whenever the screen is not visible (Home button, screen saver of Fire OS, etc.)
        resumeChannel = player.pending ?: player.current
        player.release()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------- categories & list ----------------

    private fun setCategory(index: Int) {
        if (categories.isEmpty()) return
        if (search.text.isNotEmpty()) search.setText("")   // picking a category ends the search
        catIndex = Math.floorMod(index, categories.size)
        updateChip()
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private fun updateChip() {
        val results = searchResults
        chip.text = if (results != null) getString(R.string.search_results, query, results.size)
        else categories.getOrNull(catIndex)?.let { "‹  ${it.label} · ${getString(R.string.channel_count, it.channels.size)}  ›" } ?: ""
    }

    // ---------------- search ----------------

    /** Filters all visible channels by name, ignoring accents, capitals and punctuation. */
    private val runSearch = Runnable { applySearch(search.text.toString()) }

    private fun applySearch(text: String) {
        query = text.trim()
        val needle = Sources.normalize(query)
        searchResults = if (needle.isEmpty()) null
        else app.repo.visible.filter { it.searchKey.contains(needle) }.sortedBy { it.searchKey }
        updateChip()
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        status.text = when {
            searchResults?.isEmpty() == true -> getString(R.string.search_none, query)
            player.current != null && !player.isTuning -> getString(R.string.playing)
            else -> getString(R.string.press_ok)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ---------------- jump by letter (Rewind / Fast-forward) ----------------

    private fun letterOf(c: Channel): Char =
        c.searchKey.firstOrNull()?.let { if (it.isLetter()) it.uppercaseChar() else '#' } ?: '#'

    private fun groupStart(list: List<Channel>, pos: Int): Int {
        val l = letterOf(list[pos])
        var i = pos
        while (i > 0 && letterOf(list[i - 1]) == l) i--
        return i
    }

    private fun jumpLetter(forward: Boolean) {
        val ch = channels
        if (ch.isEmpty()) return
        val pos = list.focusedChild?.let { list.getChildAdapterPosition(it) }?.takeIf { it >= 0 } ?: 0
        val target = if (forward) {
            val cur = letterOf(ch[pos])
            (pos + 1 until ch.size).firstOrNull { letterOf(ch[it]) != cur } ?: 0   // wrap to the top
        } else {
            val start = groupStart(ch, pos)
            when {
                start < pos -> start                        // first go to the start of this letter
                pos == 0 -> groupStart(ch, ch.size - 1)     // wrap to the last letter
                else -> groupStart(ch, pos - 1)             // then to the previous letter
            }
        }
        focusRow(target)
        letterView.text = letterOf(ch[target]).toString()
        letterView.visibility = View.VISIBLE
        handler.removeCallbacks(hideLetter)
        handler.postDelayed(hideLetter, 900)
    }

    private val hideLetter = Runnable { letterView.visibility = View.GONE }

    private fun focusRow(pos: Int) {
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, list.height / 3)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun focusChannel(channel: Channel) {
        val pos = channels.indexOfFirst { it.key == channel.key }
        if (pos < 0) { chip.requestFocus(); return }
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, list.height / 3)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun showInfo(channel: Channel?) {
        findViewById<TextView>(R.id.infoName).text = channel?.name ?: ""
        findViewById<TextView>(R.id.infoGroup).text = channel?.let { app.repo.groupLabel(it.mainGroup) } ?: ""
        findViewById<TextView>(R.id.infoQuality).text =
            channel?.let { listOf(it.quality, getString(R.string.source_n, it.source)).filter { s -> s.isNotEmpty() }.joinToString(" · ") } ?: ""
        val flag = findViewById<TextView>(R.id.infoFlag)
        when {
            channel?.geoBlocked == true -> { flag.setText(R.string.geo_blocked); flag.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_geo, 0, 0, 0) }
            channel?.not247 == true -> { flag.setText(R.string.not_247); flag.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0) }
            else -> { flag.text = ""; flag.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0) }
        }
    }

    private fun onChannelClicked(channel: Channel) {
        if (player.current?.key == channel.key || player.pending?.key == channel.key) enterFullscreen()
        else player.request(channel)
    }

    // ---------------- player callbacks ----------------

    override fun onTuning(channel: Channel) {
        closeMenu()
        videoMsgBox.visibility = View.VISIBLE
        videoSpinner.visibility = View.VISIBLE
        videoMsg.text = getString(R.string.tuning, channel.name)
        status.text = getString(R.string.tuning, channel.name)
        refreshPlayingMarks()
        if (fullscreen) showBanner()
    }

    override fun onPlaying(channel: Channel) {
        videoMsgBox.visibility = View.GONE
        status.setText(R.string.playing)
        refreshPlayingMarks()
    }

    override fun onFailed(channel: Channel) {
        videoMsgBox.visibility = View.VISIBLE
        videoSpinner.visibility = View.GONE
        videoMsg.text = getString(R.string.stream_failed, channel.name)
        status.text = getString(R.string.stream_failed, channel.name)
        refreshPlayingMarks()
        if (fullscreen) showBanner()
    }

    private fun refreshPlayingMarks() = adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_MARK)

    // ---------------- full screen ----------------

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        videoBox.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        videoBox.background = null
        findViewById<View>(R.id.info).visibility = View.INVISIBLE
        list.visibility = View.INVISIBLE
        videoMsg.textSize = 22f
        showBanner()
    }

    private fun exitFullscreen() {
        fullscreen = false
        videoBox.layoutParams = FrameLayout.LayoutParams(previewParams).apply { gravity = Gravity.TOP or Gravity.END }
        videoBox.setBackgroundResource(R.drawable.bg_video)
        banner.visibility = View.GONE
        findViewById<View>(R.id.info).visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        videoMsg.textSize = 15f
        (player.pending ?: player.current)?.let { focusChannel(it) } ?: chip.requestFocus()
    }

    private fun showBanner() {
        val ch = player.pending ?: player.current ?: return
        bindLogo(findViewById(R.id.bannerLogo), ch)
        findViewById<TextView>(R.id.bannerName).text = ch.name
        findViewById<TextView>(R.id.bannerMeta).text =
            listOf(app.repo.groupLabel(ch.mainGroup), ch.quality, getString(R.string.fullscreen_hint)).filter { it.isNotEmpty() }.joinToString(" · ")
        findViewById<TextView>(R.id.bannerClock).text = Formats.timeLine(Date(), app.prefs.style)
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4_000)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    // ---------------- subtitles & audio menu (☰ Menu) ----------------

    private val menuPanel: View by lazy { findViewById(R.id.trackMenu) }
    private val menuRowsBox: LinearLayout by lazy { findViewById(R.id.tmRows) }
    private var menuRows: List<View> = emptyList()
    private var menuIndex = 0
    private var menuLevel = 0          // 0 = top, else C.TRACK_TYPE_TEXT / C.TRACK_TYPE_AUDIO
    private var swallowBackUp = false
    private val menuOpen: Boolean get() = menuPanel.visibility == View.VISIBLE

    /** Shown under the Pin row after pinning / unpinning. */
    private var pinMessage = ""

    private fun openMenu() {
        pinMessage = ""
        menuPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        renderMenuTop(null)
        armMenuClose()
    }

    private fun closeMenu() {
        if (!::player.isInitialized || !menuOpen) return
        menuPanel.visibility = View.GONE
        handler.removeCallbacks(autoCloseMenu)
        if (!fullscreen) (player.current ?: player.pending)?.let { focusChannel(it) }
    }

    private fun menuBack() { if (menuLevel != 0) renderMenuTop(menuLevel) else closeMenu() }

    private val autoCloseMenu = Runnable { closeMenu() }
    private fun armMenuClose() {
        handler.removeCallbacks(autoCloseMenu)
        handler.postDelayed(autoCloseMenu, 10_000)
    }

    private fun moveMenu(step: Int) {
        if (menuRows.isEmpty()) return
        menuIndex = (menuIndex + step).coerceIn(0, menuRows.size - 1)
        menuRows[menuIndex].requestFocus()
    }

    /** Language name in the app's language ("Inglês", "English"); "CC" / "Track 2" when unknown. */
    private fun trackLabel(o: TvPlayer.TrackOption, all: List<TvPlayer.TrackOption>, position: Int): String {
        val locale = Formats.locale(app.prefs.lang)
        val base = o.language?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale) }
            ?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(locale) }
            ?: o.format.label?.takeIf { it.isNotBlank() }
            ?: if (o.group.type == C.TRACK_TYPE_TEXT) getString(R.string.cc) else getString(R.string.track_n, position + 1)
        // Tell apart tracks that share a language.
        val same = all.filter { it.language == o.language }
        if (same.size < 2) return base
        return when {
            o.format.channelCount >= 6 -> "$base (5.1)"
            !o.format.label.isNullOrBlank() && o.format.label != base -> "$base (${o.format.label})"
            else -> "$base ${same.indexOf(o) + 1}"
        }
    }

    private fun renderMenuTop(focusType: Int?) {
        menuLevel = 0
        findViewById<TextView>(R.id.tmTitle).setText(R.string.menu_title)
        findViewById<TextView>(R.id.tmChannel).text = player.current?.name ?: ""
        val subs = player.options(C.TRACK_TYPE_TEXT)
        val audio = player.options(C.TRACK_TYPE_AUDIO)
        val subsValue = when {
            subs.isEmpty() -> getString(R.string.not_available)
            else -> subs.indexOfFirst { it.selected }.let { i -> if (i < 0) getString(R.string.subs_off) else trackLabel(subs[i], subs, i) }
        }
        val audioSel = audio.indexOfFirst { it.selected }.coerceAtLeast(0)
        val audioValue = when {
            audio.isEmpty() -> getString(R.string.not_available)
            audio.size == 1 -> "${trackLabel(audio[0], audio, 0)} · ${getString(R.string.one_track)}"
            else -> trackLabel(audio[audioSel], audio, audioSel)
        }
        menuRowsBox.removeAllViews()
        val rows = ArrayList<View>()
        // Pin / Unpin the playing channel on the home screen.
        val key = player.current?.key
        val pins = app.prefs.pins
        val pinned = key != null && key in pins
        val pinRow = addMenuRow(getString(if (pinned) R.string.menu_unpin else R.string.menu_pin),
            getString(R.string.pin_count, pins.size, Pins.MAX), null, enabled = key != null, showArrow = false) { togglePin() }
        if (key != null) rows += pinRow
        if (pinMessage.isNotEmpty()) menuRowsBox.addView(TextView(this).apply {
            text = pinMessage; textSize = 13f; setTextColor(getColor(R.color.ok))
            setPadding(dp(4), 0, 0, dp(12))
        })
        val subsRow = addMenuRow(getString(R.string.menu_subs), subsValue, null, enabled = subs.isNotEmpty()) { renderMenuList(C.TRACK_TYPE_TEXT) }
        val audioRow = addMenuRow(getString(R.string.menu_audio), audioValue, null, enabled = audio.size > 1) { renderMenuList(C.TRACK_TYPE_AUDIO) }
        if (subs.isNotEmpty()) rows += subsRow
        if (audio.size > 1) rows += audioRow
        menuRows = rows
        menuIndex = when (focusType) {
            C.TRACK_TYPE_AUDIO -> rows.indexOf(audioRow)
            C.TRACK_TYPE_TEXT -> rows.indexOf(subsRow)
            else -> 0                                  // the Pin row
        }.coerceAtLeast(0)
        if (rows.isEmpty()) menuPanel.requestFocus() else rows[menuIndex].requestFocus()
    }

    /** Newest pin goes first; a sixth pin drops the oldest, and the menu says which one. */
    private fun togglePin() {
        val ch = player.current ?: return
        val pins = app.prefs.pins
        if (ch.key in pins) {
            app.prefs.pins = Pins.remove(pins, ch.key)
            pinMessage = getString(R.string.unpinned_msg)
        } else {
            val (list, dropped) = Pins.add(pins, ch.key)
            app.prefs.pins = list
            val droppedName = dropped?.let { k -> app.repo.find(k)?.name ?: k.substringAfter('|') }
            pinMessage = if (droppedName != null) getString(R.string.pin_dropped, droppedName) else getString(R.string.pinned_msg)
        }
        renderMenuTop(null)
    }

    private fun renderMenuList(type: Int) {
        menuLevel = type
        val isText = type == C.TRACK_TYPE_TEXT
        findViewById<TextView>(R.id.tmTitle).setText(if (isText) R.string.menu_subs else R.string.menu_audio)
        val opts = player.options(type)
        menuRowsBox.removeAllViews()
        val rows = ArrayList<View>()
        var sel = 0
        if (isText) {
            val off = opts.none { it.selected }
            rows += addMenuRow(getString(R.string.subs_off), null, off, enabled = true) {
                player.subtitlesOff(); app.prefs.subsLang = "off"; afterPick(type)
            }
        }
        opts.forEachIndexed { i, o ->
            if (o.selected) sel = rows.size
            rows += addMenuRow(trackLabel(o, opts, i), null, o.selected, enabled = true) {
                player.select(o)
                if (isText) app.prefs.subsLang = o.language ?: "und" else app.prefs.audioLang = o.language ?: ""
                afterPick(type)
            }
        }
        menuRows = rows
        menuIndex = sel
        rows.getOrNull(sel)?.requestFocus()
    }

    /** Back to the top level; the player applies the change a moment later, so refresh again then. */
    private fun afterPick(type: Int) {
        renderMenuTop(type)
        handler.postDelayed({ if (menuOpen && menuLevel == 0) renderMenuTop(type) }, 300)
    }

    private fun addMenuRow(label: String, value: String?, checked: Boolean?, enabled: Boolean, showArrow: Boolean = true, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_menu)
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.5f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }
        if (checked != null) row.addView(TextView(this).apply {
            text = if (checked) "✓" else ""
            textSize = 17f
            setTextColor(getColor(R.color.focus))
        }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = label; textSize = 17f; isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.row_text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) row.addView(TextView(this).apply {
            text = if (enabled && showArrow) "$value  ›" else value
            textSize = 14f; isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.row_text_muted))
        })
        menuRowsBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return row
    }

    /** Subtitle size: Fire TV's own caption settings, or a fixed Small / Medium / Large. */
    private fun applySubtitleStyle() {
        val sv = findViewById<PlayerView>(R.id.playerView).subtitleView ?: return
        when (val size = app.prefs.subSize) {
            "sys" -> { sv.setUserDefaultStyle(); sv.setUserDefaultTextSize() }
            else -> {
                sv.setStyle(CaptionStyleCompat.DEFAULT)
                val f = when (size) { "s" -> 0.75f; "l" -> 1.35f; else -> 1f }
                sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * f)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySubtitleStyle()
    }

    private fun zap(step: Int) {
        val list = channels
        if (list.isEmpty()) return
        val from = (player.pending ?: player.current)?.key
        val i = list.indexOfFirst { it.key == from }
        val next = list[Math.floorMod((if (i < 0) 0 else i) + step, list.size)]
        player.request(next, TvPlayer.ZAP_SETTLE_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The Back that closed the menu must not also leave full screen when the key is released.
        if (swallowBackUp && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            swallowBackUp = false
            return true
        }
        if (menuOpen) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                armMenuClose()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> moveMenu(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveMenu(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> menuRows.getOrNull(menuIndex)?.performClick()
                    KeyEvent.KEYCODE_BACK -> { swallowBackUp = true; menuBack() }
                    KeyEvent.KEYCODE_MENU -> closeMenu()
                }
            }
            return true   // nothing else reacts while the menu is open
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && ready) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (player.current != null && !player.isTuning) openMenu() else if (fullscreen) showBanner()
            }
            return true
        }
        val isJumpKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        if (isJumpKey && !fullscreen && ready) {
            if (event.action == KeyEvent.ACTION_DOWN) jumpLetter(event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            return true
        }
        if (fullscreen && ready && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showBanner(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- adapter ----------------

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        init { setHasStableIds(true) }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: View = v.findViewById(R.id.rowLogo)
            val name: TextView = v.findViewById(R.id.rowName)
            val playing: View = v.findViewById(R.id.rowPlaying)
            val group: TextView = v.findViewById(R.id.rowGroup)
            val quality: TextView = v.findViewById(R.id.rowQuality)
            val source: TextView = v.findViewById(R.id.rowSource)
            var channel: Channel? = null

            init {
                v.setOnClickListener { channel?.let(::onChannelClicked) }
                v.setOnFocusChangeListener { _, has -> if (has) showInfo(channel) }
            }
        }

        override fun getItemCount() = channels.size
        override fun getItemId(position: Int) = channels[position].key.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_MARK)) bindMark(holder) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = channels[position]
            holder.channel = c
            holder.name.text = c.name
            holder.group.text = app.repo.groupLabel(c.mainGroup)
            holder.quality.text = c.quality
            holder.source.text = c.source.toString()
            bindLogo(holder.logo, c)
            bindMark(holder)
        }

        private fun bindMark(holder: VH) {
            val c = holder.channel
            holder.playing.visibility =
                if (c != null && player.current?.key == c.key && !player.isTuning) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val SEARCH_DELAY_MS = 3_000L
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_FULLSCREEN = "fullscreen"
        private const val PAYLOAD_MARK = "mark"
    }
}
