package com.vanisetv.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanisetv.app.R
import com.vanisetv.app.data.Channel
import com.vanisetv.app.data.Sources
import kotlinx.coroutines.launch

/**
 * Every channel in the local file with a show/hide switch. Saves to channels.m3u immediately.
 * The search box filters by name (ignoring accents, capitals and punctuation); Back clears it.
 */
class EditorActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var all: List<Channel> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var query = ""
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        list = findViewById(R.id.list)
        count = findViewById(R.id.count)
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null

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
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
                if (channels.isNotEmpty()) list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
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
            updateCount()
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val runSearch = Runnable { applySearch(search.text.toString()) }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applySearch(text: String) {
        query = text.trim()
        val needle = Sources.normalize(query)
        channels = if (needle.isEmpty()) all else all.filter { it.searchKey.contains(needle) }
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        empty.text = getString(R.string.search_none, query)
        empty.visibility = if (channels.isEmpty() && query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCount() {
        val hidden = all.count { it.hidden }
        count.text = getString(R.string.editor_count, all.size - hidden, hidden)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: View = v.findViewById(R.id.edLogo)
            val name: TextView = v.findViewById(R.id.edName)
            val group: TextView = v.findViewById(R.id.edGroup)
            val state: TextView = v.findViewById(R.id.edState)
            val switch: ImageView = v.findViewById(R.id.edSwitch)

            init {
                v.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val c = channels[pos]
                    app.repo.setHidden(c, !c.hidden)
                    notifyItemChanged(pos)
                    updateCount()
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
