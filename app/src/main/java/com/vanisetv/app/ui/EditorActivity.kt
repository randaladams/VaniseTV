package com.vanisetv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanisetv.app.R
import com.vanisetv.app.data.Channel
import kotlinx.coroutines.launch

/** Every channel in the local file with a show/hide switch. Saves to channels.m3u immediately. */
class EditorActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var count: TextView
    private var channels: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        list = findViewById(R.id.list)
        count = findViewById(R.id.count)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        lifecycleScope.launch {
            try { app.repo.ensureLoaded() } catch (e: Exception) { finish(); return@launch }
            channels = app.repo.channels.sortedBy { it.name.lowercase() }
            list.adapter = Adapter()
            updateCount()
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun updateCount() {
        val hidden = channels.count { it.hidden }
        count.text = getString(R.string.editor_count, channels.size - hidden, hidden)
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
            h.group.text = listOf(app.repo.groupLabel(c.mainGroup), c.quality, getString(R.string.source_n, c.source)).filter { it.isNotEmpty() }.joinToString(" · ")
            h.state.setText(if (c.hidden) R.string.hidden else R.string.shown)
            h.switch.setImageResource(if (c.hidden) R.drawable.switch_off else R.drawable.switch_on)
        }
    }
}
