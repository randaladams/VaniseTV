package com.vanisetv.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.vanisetv.app.App
import java.util.Date
import kotlin.random.Random

/**
 * Shared behaviour for every screen: app language, remote click sounds and the screen saver.
 */
abstract class BaseActivity : ComponentActivity() {
    protected val app: App get() = App.instance
    private var createdLang = ""
    private val handler = Handler(Looper.getMainLooper())
    private var lastInput = SystemClock.elapsedRealtime()
    private var saver: View? = null
    private var saverShownAt = 0L

    /** True while a channel is playing; suppresses click sounds and the screen saver. */
    protected open val isPlaybackActive: Boolean get() = false

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "en") ?: "en"
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Formats.locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = app.prefs.lang
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (createdLang != app.prefs.lang) { recreate(); return }
        lastInput = SystemClock.elapsedRealtime()
        handler.post(saverCheck)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        hideSaver()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInput = SystemClock.elapsedRealtime()
            if (saver != null) { hideSaver(); swallowNextUp = true; return true }
            if (event.repeatCount == 0 && isNavKey(event.keyCode) && !isPlaybackActive) app.playClick()
        } else if (saver == null && event.action == KeyEvent.ACTION_UP && swallowNextUp) {
            swallowNextUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private var swallowNextUp = false

    protected fun isNavKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    // ---------------- screen saver ----------------

    private val saverCheck = object : Runnable {
        override fun run() {
            val minutes = app.prefs.saverMinutes
            val idle = SystemClock.elapsedRealtime() - lastInput
            if (saver == null && minutes > 0 && !isPlaybackActive && idle >= minutes * 60_000L) showSaver()
            if (saver != null) {
                updateSaver()
                // After an hour of screen saver, let Fire TV put the TV to sleep.
                if (SystemClock.elapsedRealtime() - saverShownAt > 60 * 60_000L) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            handler.postDelayed(this, if (saver != null) 1_000 else 10_000)
        }
    }

    private lateinit var saverTime: TextView
    private lateinit var saverDate: TextView
    private lateinit var saverBox: LinearLayout
    private var lastMove = 0L

    private fun showSaver() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = resources.displayMetrics.density
        saverTime = TextView(this).apply { textSize = 64f; setTextColor(Color.parseColor("#4B5A66")); typeface = android.graphics.Typeface.create("sans-serif-light", 0) }
        saverDate = TextView(this).apply { textSize = 20f; setTextColor(Color.parseColor("#4B5A66")) }
        saverBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(saverTime); addView(saverDate)
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(saverBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (60 * d).toInt(); topMargin = (60 * d).toInt()
            })
        }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        saver = overlay
        saverShownAt = SystemClock.elapsedRealtime()
        lastMove = 0
        updateSaver()
    }

    private fun updateSaver() {
        val now = Date()
        saverTime.text = Formats.timeLine(now, app.prefs.style)
        saverDate.text = "${Formats.dayName(now, app.prefs.lang)}, ${Formats.date(now, app.prefs.style)}"
        // Move the clock every 20 seconds so nothing burns into the panel.
        if (SystemClock.elapsedRealtime() - lastMove > 20_000) {
            lastMove = SystemClock.elapsedRealtime()
            val root = saver ?: return
            val w = root.width; val h = root.height
            if (w > 0 && h > 0) {
                val lp = saverBox.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = Random.nextInt(0, (w * 0.65).toInt().coerceAtLeast(1))
                lp.topMargin = Random.nextInt(0, (h * 0.7).toInt().coerceAtLeast(1))
                saverBox.layoutParams = lp
            }
        }
    }

    private fun hideSaver() {
        val v = saver ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        saver = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
