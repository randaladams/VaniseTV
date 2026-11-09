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
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanisetv.app.R
import com.vanisetv.app.data.Category
import com.vanisetv.app.data.Channel
import com.vanisetv.app.data.Sources
import kotlinx.coroutines.launch
import java.util.Date

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
            override fun afterTextChanged(s: Editable?) { if (ready) applySearch(s?.toString().orEmpty()) }
        })
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
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
                    query.isNotEmpty() -> { search.setText(""); search.requestFocus() }   // first Back clears the search
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
        if (query.isNotEmpty()) search.setText("")   // picking a category ends the search
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
    private fun applySearch(text: String) {
        query = text.trim()
        val needle = Sources.normalize(query)
        searchResults = if (needle.isEmpty()) null
        else app.repo.visible.filter { Sources.normalize(it.name).contains(needle) }.sortedBy { Sources.normalize(it.name) }
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
        Sources.normalize(c.name).firstOrNull()?.let { if (it.isLetter()) it.uppercaseChar() else '#' } ?: '#'

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

    private fun zap(step: Int) {
        val list = channels
        if (list.isEmpty()) return
        val from = (player.pending ?: player.current)?.key
        val i = list.indexOfFirst { it.key == from }
        val next = list[Math.floorMod((if (i < 0) 0 else i) + step, list.size)]
        player.request(next, TvPlayer.ZAP_SETTLE_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> { showBanner(); return true }
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
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_FULLSCREEN = "fullscreen"
        private const val PAYLOAD_MARK = "mark"
    }
}
