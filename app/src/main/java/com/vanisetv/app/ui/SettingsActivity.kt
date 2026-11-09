package com.vanisetv.app.ui

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vanisetv.app.BuildConfig
import com.vanisetv.app.R
import com.vanisetv.app.data.Backgrounds
import com.vanisetv.app.data.Prefs
import com.vanisetv.app.data.Sources

class SettingsActivity : BaseActivity() {
    private enum class Section { TIME, LANG, SOUND, SAVER, SOURCES, ABOUT }

    private lateinit var menu: LinearLayout
    private lateinit var options: LinearLayout
    private val menuViews = HashMap<Section, View>()
    private var section = Section.TIME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        menu = findViewById(R.id.menu)
        options = findViewById(R.id.options)
        section = savedInstanceState?.getString("section")?.let { Section.valueOf(it) } ?: Section.TIME

        listOf(
            Section.TIME to R.string.s_time, Section.LANG to R.string.s_lang, Section.SOUND to R.string.s_sound,
            Section.SAVER to R.string.s_saver, Section.SOURCES to R.string.s_sources, Section.ABOUT to R.string.s_about,
        ).forEach { (s, label) -> menuViews[s] = addMenuItem(s, getString(label)) }

        refreshMenuValues()
        showSection(section)
        menuViews[section]?.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("section", section.name)
    }

    private fun addMenuItem(s: Section, label: String): View {
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_menu)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 19f; isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.row_text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            tag = "value"; textSize = 14f; isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.row_text_muted))
        })
        row.setOnFocusChangeListener { _, has -> if (has && section != s) showSection(s) }
        row.setOnClickListener { options.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus() }
        menu.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return row
    }

    private fun refreshMenuValues() {
        val p = app.prefs
        fun set(s: Section, v: String) { menuViews[s]?.findViewWithTag<TextView>("value")?.text = v }
        set(Section.TIME, getString(if (p.style == Prefs.STYLE_US) R.string.style_us else R.string.style_br))
        set(Section.LANG, if (p.lang == "pt") "Português" else "English")
        set(Section.SOUND, getString(if (p.clicks) R.string.on else R.string.off))
        set(Section.SAVER, saverLabel(p.saverMinutes))
        set(Section.SOURCES, listOf(p.source1, p.source2).count { it.isNotBlank() }.toString())
        set(Section.ABOUT, "v${BuildConfig.VERSION_NAME}")
    }

    private fun saverLabel(m: Int) = getString(
        when (m) { 3 -> R.string.saver_3; 10 -> R.string.saver_10; 60 -> R.string.saver_60; else -> R.string.saver_never }
    )

    private fun showSection(s: Section) {
        section = s
        menuViews.forEach { (k, v) -> v.isSelected = k == s }
        options.removeAllViews()
        val title = findViewById<TextView>(R.id.panelTitle)
        val hint = findViewById<TextView>(R.id.panelHint)
        val p = app.prefs
        when (s) {
            Section.TIME -> {
                title.setText(R.string.s_time); hint.setText(R.string.hint_time)
                addOption(getString(R.string.style_us), "7:45 PM · 11/09/2026", p.style == Prefs.STYLE_US) { p.style = Prefs.STYLE_US }
                addOption(getString(R.string.style_br), "19:45 · 09/11/2026", p.style == Prefs.STYLE_BR) { p.style = Prefs.STYLE_BR }
            }
            Section.LANG -> {
                title.setText(R.string.s_lang); hint.setText(R.string.hint_lang)
                addOption("English", "", p.lang == "en") { setLang("en") }
                addOption("Português (Brasil)", "", p.lang == "pt") { setLang("pt") }
            }
            Section.SOUND -> {
                title.setText(R.string.s_sound); hint.setText(R.string.hint_sound)
                addOption(getString(R.string.on), "", p.clicks) { p.clicks = true }
                addOption(getString(R.string.off), "", !p.clicks) { p.clicks = false }
            }
            Section.SAVER -> {
                title.setText(R.string.s_saver); hint.setText(R.string.hint_saver)
                listOf(3, 10, 60, 0).forEach { m -> addOption(saverLabel(m), "", p.saverMinutes == m) { p.saverMinutes = m } }
            }
            Section.SOURCES -> {
                title.setText(R.string.s_sources); hint.setText(R.string.hint_sources)
                val stats = p.lastSourceStats.split('|').mapNotNull { it.toIntOrNull() }
                addSourceField(1, p.source1, stats.getOrNull(0)?.let { getString(R.string.source_stat_1, it) })
                addSourceField(2, p.source2, if (stats.size == 4) getString(R.string.source_stat_2, stats[1], stats[2], stats[3]) else null)
                addButton(getString(R.string.restore_sources)) {
                    p.source1 = Sources.DEFAULT_1
                    p.source2 = Sources.DEFAULT_2
                }
                addNote(getString(R.string.sources_note))
            }
            Section.ABOUT -> {
                title.setText(R.string.s_about); hint.text = ""
                val repo = app.repo
                addInfo(R.string.about_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                addInfo(R.string.about_device, "${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                addInfo(R.string.about_active, getString(R.string.about_active_value, repo.visible.size, repo.channels.size))
                addInfo(R.string.about_downloaded, if (p.lastDownload > 0) Formats.dateTime(p.lastDownload, p.style) else getString(R.string.about_never))
                addInfo(R.string.about_by_source, getString(R.string.about_by_source_value,
                    repo.channels.count { it.source == 1 }, repo.channels.count { it.source == 2 }))
                addInfo(R.string.about_backgrounds, getString(R.string.about_backgrounds_value, Backgrounds.list(this).size))
            }
        }
    }

    private fun setLang(lang: String) {
        if (app.prefs.lang == lang) return
        app.prefs.lang = lang
        recreate()
    }

    private fun addOption(label: String, example: String, checked: Boolean, apply: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_option)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(13), dp(18), dp(13))
            nextFocusLeftId = menuViews[section]?.id ?: View.NO_ID
        }
        row.addView(ImageView(this).apply { setImageResource(if (checked) R.drawable.radio_on else R.drawable.radio_off) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        row.addView(TextView(this).apply { text = label; textSize = 19f; setTextColor(getColor(R.color.fg)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        if (example.isNotEmpty()) row.addView(TextView(this).apply { text = example; textSize = 16f; setTextColor(getColor(R.color.muted)) })
        val index = options.childCount
        row.setOnClickListener {
            apply()
            refreshMenuValues()
            showSection(section)
            options.getChildAt(index)?.requestFocus()
        }
        options.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    /** A focusable row showing a source address; OK opens a text box to change it. */
    private fun addSourceField(n: Int, url: String, stat: String?) {
        val index = options.childCount
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_option)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(13), dp(18), dp(13))
            nextFocusLeftId = menuViews[section]?.id ?: View.NO_ID
        }
        row.addView(TextView(this).apply { text = getString(R.string.source_n, n); textSize = 19f; setTextColor(getColor(R.color.fg)) },
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = url.ifBlank { getString(R.string.source_empty) }
            textSize = 16f
            setTextColor(getColor(if (url.isBlank()) R.color.muted else R.color.fg))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val dlg = TvDialog(this)
            val save: () -> Unit = {
                if (n == 1) app.prefs.source1 = dlg.inputText else app.prefs.source2 = dlg.inputText
                dlg.dismiss()
                refreshMenuValues()
                showSection(section)
                options.getChildAt(index)?.requestFocus()
            }
            dlg.content(getString(R.string.edit_source_title, n), getString(R.string.sources_note))
                .buttons(getString(R.string.save), save, getString(R.string.cancel), { dlg.dismiss() })
                .input(url, save)
                .show()
        }
        options.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        options.addView(TextView(this).apply {
            text = stat ?: ""
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding(dp(128), 0, 0, dp(12))
        })
    }

    private fun addButton(label: String, apply: () -> Unit) {
        val index = options.childCount
        val b = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(getColorStateList(R.color.button_text))
            setBackgroundResource(R.drawable.bg_button)
            isFocusable = true
            isClickable = true
            setPadding(dp(22), dp(10), dp(22), dp(10))
            nextFocusLeftId = menuViews[section]?.id ?: View.NO_ID
            setOnClickListener {
                apply()
                refreshMenuValues()
                showSection(section)
                options.getChildAt(index)?.requestFocus()
            }
        }
        options.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun addNote(text: String) {
        options.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.muted))
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun addInfo(label: Int, value: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
        row.addView(TextView(this).apply { setText(label); textSize = 17f; setTextColor(getColor(R.color.muted)) },
            LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply { text = value; textSize = 17f; setTextColor(getColor(R.color.fg)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        options.addView(row)
    }
}
