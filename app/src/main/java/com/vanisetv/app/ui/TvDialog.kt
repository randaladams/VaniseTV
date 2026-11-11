package com.vanisetv.app.ui

import android.app.Dialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vanisetv.app.App
import com.vanisetv.app.R

/**
 * Remote-friendly dialog: title, body, optional progress bar, optional text input and buttons.
 * Two buttons sit side by side; three or more are stacked as a list.
 */
class TvDialog(private val activity: BaseActivity) {
    private val view = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
    private val dialog = object : Dialog(activity, R.style.Dialog_VaniseTV) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (super.dispatchKeyEvent(event)) return true
            // Move focus ourselves so Fire TV doesn't add its own click (see BaseActivity).
            return event.action == KeyEvent.ACTION_DOWN && moveFocusSilently(currentFocus, event.keyCode)
        }
    }.apply { setContentView(view) }
    private val title: TextView = view.findViewById(R.id.dlgTitle)
    private val body: TextView = view.findViewById(R.id.dlgBody)
    private val progress: ProgressBar = view.findViewById(R.id.dlgProgress)
    private val buttons: LinearLayout = view.findViewById(R.id.dlgButtons)

    /** Called on Back. Return true to consume. Default: dismiss. */
    var onBack: (() -> Boolean)? = null

    init {
        dialog.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) App.instance.playClick()
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (onBack?.invoke() != true) dialog.dismiss()
                true
            } else code == KeyEvent.KEYCODE_BACK
        }
    }

    fun content(titleText: String, bodyText: String): TvDialog {
        title.text = titleText
        setBody(bodyText)
        return this
    }

    fun setBody(bodyText: String) {
        body.text = bodyText
        body.visibility = if (bodyText.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Buttons in order; [focus] is the index selected first. */
    fun choices(focus: Int, vararg items: Pair<String, () -> Unit>): TvDialog {
        progress.visibility = View.GONE
        buttons.visibility = View.VISIBLE
        buttons.removeAllViews()
        val stacked = items.size > 2
        buttons.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        fun button(parent: LinearLayout, label: String, action: () -> Unit): TextView {
            val b = LayoutInflater.from(activity).inflate(R.layout.item_dialog_button, parent, false) as TextView
            b.text = label
            b.setOnClickListener { action() }
            return b
        }
        val views = if (items.size >= 4) {
            // A 2-column grid: four stacked buttons don't fit on the screen under a long message.
            items.toList().chunked(2).flatMapIndexed { r, pair ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (r > 0) topMargin = activity.dp(8) })
                pair.mapIndexed { c, (label, action) ->
                    button(row, label, action).also {
                        row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { if (c > 0) marginStart = activity.dp(8) })
                    }
                }
            }
        } else items.mapIndexed { i, (label, action) ->
            val lp = if (stacked) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) topMargin = activity.dp(8) }
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = activity.dp(14) }
            button(buttons, label, action).also { buttons.addView(it, lp) }
        }
        views.getOrNull(focus)?.requestFocus()
        return this
    }

    fun buttons(primaryText: String?, onPrimary: () -> Unit, secondaryText: String? = null, onSecondary: () -> Unit = { dismiss() }, focusSecondary: Boolean = false): TvDialog {
        val items = listOfNotNull(primaryText?.let { it to onPrimary }, secondaryText?.let { it to onSecondary })
        return choices(if (focusSecondary && items.size > 1) 1 else 0, *items.toTypedArray())
    }

    private val input: EditText = view.findViewById(R.id.dlgInput)

    /** Shows a one-line text field (opens the Fire TV keyboard). Read it back with [inputText]. */
    fun input(text: String, onDone: () -> Unit): TvDialog {
        input.visibility = View.VISIBLE
        input.setText(text)
        input.setSelection(text.length)
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
        return this
    }

    val inputText: String get() = input.text.toString()

    /** Hides the buttons and shows a moving progress bar. */
    fun busy(): TvDialog {
        buttons.visibility = View.GONE
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        return this
    }

    /** Progress bar that fills as [done] approaches [total]. */
    fun progress(done: Int, total: Int) {
        buttons.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.max = total.coerceAtLeast(1)
        progress.progress = done
    }

    fun show(): TvDialog { if (!activity.isFinishing) dialog.show(); return this }
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
}
