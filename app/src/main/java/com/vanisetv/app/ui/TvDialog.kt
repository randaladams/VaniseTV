package com.vanisetv.app.ui

import android.app.Dialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.vanisetv.app.App
import com.vanisetv.app.R

/** Remote-friendly dialog: title, body, optional progress bar and up to two buttons. */
class TvDialog(private val activity: BaseActivity) {
    private val view = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
    private val dialog = Dialog(activity, R.style.Dialog_VaniseTV).apply { setContentView(view) }
    private val title: TextView = view.findViewById(R.id.dlgTitle)
    private val body: TextView = view.findViewById(R.id.dlgBody)
    private val progress: ProgressBar = view.findViewById(R.id.dlgProgress)
    private val buttons: View = view.findViewById(R.id.dlgButtons)
    private val primary: TextView = view.findViewById(R.id.dlgPrimary)
    private val secondary: TextView = view.findViewById(R.id.dlgSecondary)

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
        body.text = bodyText
        body.visibility = if (bodyText.isEmpty()) View.GONE else View.VISIBLE
        return this
    }

    fun buttons(primaryText: String?, onPrimary: () -> Unit, secondaryText: String? = null, onSecondary: () -> Unit = { dismiss() }, focusSecondary: Boolean = false): TvDialog {
        progress.visibility = View.GONE
        buttons.visibility = View.VISIBLE
        primary.visibility = if (primaryText == null) View.GONE else View.VISIBLE
        primary.text = primaryText
        primary.setOnClickListener { onPrimary() }
        secondary.visibility = if (secondaryText == null) View.GONE else View.VISIBLE
        secondary.text = secondaryText
        secondary.setOnClickListener { onSecondary() }
        (if (focusSecondary && secondaryText != null) secondary else primary).requestFocus()
        return this
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

    fun busy(): TvDialog {
        buttons.visibility = View.GONE
        progress.visibility = View.VISIBLE
        return this
    }

    fun show(): TvDialog { if (!activity.isFinishing) dialog.show(); return this }
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
}
