package com.vanisetv.app.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanisetv.app.R
import com.vanisetv.app.data.Channel
import com.vanisetv.app.data.ShowFilter
import com.vanisetv.app.data.Sources
import kotlinx.coroutines.launch

/**
 * Channel Editor, works like Live TV: lands on the first shown channel and plays a preview
 * without sound after the usual settle time. OK shows or hides the channel (a hidden channel
 * never previews; hiding stops the preview at once). ☰ Menu jumps to Search.
 * All / Shown / Hidden filter and search work together. Saves to channels.m3u immediately.
 */
class EditorActivity : BaseActivity(), TvPlayer.Callback {
    private lateinit var list: RecyclerView
    private lateinit var chips: Map<ShowFilter, TextView>
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var player: TvPlayer
    private lateinit var videoMsgBox: View
    private lateinit var videoMsg: TextView
    private lateinit var videoSpinner: View
    private lateinit var videoBadge: TextView
    private var all: List<Channel> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var query = ""
    private var filter = ShowFilter.ALL
    /** Keys of channels switched under the current filter; they stay listed until it changes. */
    private val switched = HashSet<String>()
    private val adapter = Adapter()
    private var ready = false
    private var lastMoveAt = 0L
    /** The row that has (or last had) focus, so the preview can resume after the screen saver. */
    private var focusedChannel: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        list = findViewById(R.id.list)
        chips = mapOf(
            ShowFilter.ALL to findViewById<TextView>(R.id.filterAll),
            ShowFilter.SHOWN to findViewById<TextView>(R.id.filterShown),
            ShowFilter.HIDDEN to findViewById<TextView>(R.id.filterHidden),
        )
        chips.forEach { (f, chip) -> chip.setOnClickListener { setFilter(f) } }
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        videoMsgBox = findViewById(R.id.videoMsgBox)
        videoMsg = findViewById(R.id.videoMsg)
        videoSpinner = findViewById(R.id.videoSpinner)
        videoBadge = findViewById(R.id.videoBadge)
        player = TvPlayer(this, findViewById<PlayerView>(R.id.playerView), this, silent = true)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        videoMsg.setText(R.string.ep_nothing)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(runSearch)
                if (s.isNullOrBlank()) applySearch("")          // clearing is immediate
                else {
                    // Wait until typing pauses so each key press stays fast.
                    empty.setText(R.string.search_wait)
                    empty.visibility = View.VISIBLE
                    handler.postDelayed(runSearch, GuideActivity.SEARCH_DELAY_MS)
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
                if (search.text.isNotEmpty()) { search.setText(""); search.requestFocus() }   // first Back clears the search
                else finish()
            }
        })

        lifecycleScope.launch {
            try { app.repo.ensureLoaded() } catch (e: Exception) { finish(); return@launch }
            all = app.repo.channels.sortedBy { it.searchKey }
            channels = all
            list.adapter = adapter
            updateChips()
            ready = true
            // Land on the first shown channel, like Live TV.
            focusRow(channels.indexOfFirst { !it.hidden }.coerceAtLeast(0))
        }
    }

    override fun onStart() {
        super.onStart()
        player.create()
        focusedChannel?.let(::previewIfShown)
    }

    override fun onStop() {
        super.onStop()
        player.release()   // free the decoder whenever the screen isn't visible
    }

    override fun onSaverShown() = player.stop()
    override fun onSaverHidden() { focusedChannel?.let(::previewIfShown) }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val runSearch = Runnable { applySearch(search.text.toString()) }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ready && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // ☰ Menu: jump up to Search and open the keyboard.
                KeyEvent.KEYCODE_MENU -> { openSearch(); return true }
                // One row at a time, however fast the remote is pressed or held (same pace as Live TV).
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastMoveAt < GuideActivity.MOVE_LIMIT_MS) return true
                    lastMoveAt = now
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.dispatchKeyEvent(event)
    }

    private fun openSearch() {
        search.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun focusRow(pos: Int) {
        if (pos < 0 || pos >= channels.size) return
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, list.height / 3)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    // ---------------- preview ----------------

    /** A row got focus: show its details; preview it only if it is shown. */
    private fun onRowFocused(c: Channel) {
        focusedChannel = c
        showInfo(c)
        previewIfShown(c)
    }

    private fun previewIfShown(c: Channel) {
        if (c.hidden) {
            player.stop()
            showMessage(getString(R.string.ep_hidden_msg), spinner = false)
        } else if (player.current?.key != c.key && player.pending?.key != c.key) {
            player.request(c)   // starts after the usual settle time
        }
    }

    private fun showInfo(c: Channel) {
        findViewById<TextView>(R.id.infoName).text = c.name
        findViewById<TextView>(R.id.infoMeta).text = listOf(app.repo.groupLabel(c.mainGroup), c.quality, getString(R.string.source_n, c.source))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val flag = findViewById<TextView>(R.id.infoFlag)
        when {
            c.geoBlocked -> { flag.setText(R.string.geo_blocked); flag.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_geo, 0, 0, 0) }
            c.not247 -> { flag.setText(R.string.not_247); flag.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0) }
            else -> { flag.text = ""; flag.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0) }
        }
        val state = findViewById<TextView>(R.id.infoState)
        state.text = "● " + getString(if (c.hidden) R.string.hidden else R.string.shown)
        state.setTextColor(getColor(if (c.hidden) R.color.muted else R.color.ok))
        findViewById<TextView>(R.id.infoHint).setText(if (c.hidden) R.string.ep_hidden_hint else R.string.ep_shown_hint)
    }

    private fun showMessage(text: String, spinner: Boolean) {
        videoMsgBox.visibility = View.VISIBLE
        videoSpinner.visibility = if (spinner) View.VISIBLE else View.GONE
        videoMsg.text = text
        videoBadge.visibility = View.GONE
    }

    override fun onTuning(channel: Channel) = showMessage(getString(R.string.tuning, channel.name), spinner = true)

    override fun onPlaying(channel: Channel) {
        videoMsgBox.visibility = View.GONE
        videoBadge.text = "🔇 " + getString(R.string.ep_muted, channel.name)
        videoBadge.visibility = View.VISIBLE
    }

    override fun onFailed(channel: Channel) = showMessage(getString(R.string.stream_failed, channel.name), spinner = false)

    // ---------------- filter & search ----------------

    private fun applySearch(text: String) {
        query = text.trim()
        refreshList()
    }

    private fun setFilter(f: ShowFilter) {
        filter = f
        switched.clear()
        refreshList()
        updateChips()
    }

    /** Applies the filter and the search together. */
    private fun refreshList() {
        val needle = Sources.normalize(query)
        channels = all.filter { filter.matches(it.hidden, it.key in switched) && (needle.isEmpty() || it.searchKey.contains(needle)) }
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        empty.text = when {
            query.isNotEmpty() -> getString(R.string.search_none, query)
            filter == ShowFilter.SHOWN -> getString(R.string.editor_none_shown)
            filter == ShowFilter.HIDDEN -> getString(R.string.editor_none_hidden)
            else -> ""
        }
        empty.visibility = if (channels.isEmpty() && empty.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateChips() {
        val hidden = all.count { it.hidden }
        chips.forEach { (f, chip) ->
            val (label, n) = when (f) {
                ShowFilter.ALL -> R.string.editor_all to all.size
                ShowFilter.SHOWN -> R.string.editor_shown to all.size - hidden
                ShowFilter.HIDDEN -> R.string.editor_hidden to hidden
            }
            chip.text = (if (f == filter) "✓ " else "") + getString(label, n)
            chip.isSelected = f == filter
        }
    }

    // ---------------- list ----------------

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: View = v.findViewById(R.id.edLogo)
            val name: TextView = v.findViewById(R.id.edName)
            val group: TextView = v.findViewById(R.id.edGroup)
            val state: TextView = v.findViewById(R.id.edState)
            val switch: ImageView = v.findViewById(R.id.edSwitch)

            init {
                v.setOnFocusChangeListener { _, has ->
                    if (has) channels.getOrNull(bindingAdapterPosition)?.let(::onRowFocused)
                }
                // OK: show or hide. Hiding stops the preview at once; showing starts it after the usual wait.
                v.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val c = channels[pos]
                    app.repo.setHidden(c, !c.hidden)
                    if (filter != ShowFilter.ALL) switched += c.key
                    notifyItemChanged(pos)
                    updateChips()
                    showInfo(c)
                    previewIfShown(c)
                }
            }
        }

        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_editor, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = channels[position]
            bindLogo(h.logo, c)
            h.logo.alpha = if (c.hidden) 0.45f else 1f
            h.name.text = c.name
            h.name.setTextColor(getColor(if (c.hidden) R.color.muted else R.color.fg))
            h.name.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (c.geoBlocked) R.drawable.ic_geo else 0, 0)
            h.group.text = listOf(app.repo.groupLabel(c.mainGroup), c.quality, getString(R.string.source_n, c.source))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            h.state.setText(if (c.hidden) R.string.hidden else R.string.shown)
            h.switch.setImageResource(if (c.hidden) R.drawable.switch_off else R.drawable.switch_on)
        }
    }
}
