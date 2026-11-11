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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import coil.load
import com.vanisetv.app.App
import com.vanisetv.app.R
import com.vanisetv.app.data.Backgrounds
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
        if (super.dispatchKeyEvent(event)) return true
        // Arrow keys nobody used: move the highlight ourselves. When Android does it, Fire TV plays
        // its own navigation click, which made the "Remote click sounds" setting seem to do nothing.
        return event.action == KeyEvent.ACTION_DOWN && moveFocusSilently(currentFocus, event.keyCode)
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

    /** Called when the screen saver comes on (the Channel Editor stops its preview then). */
    protected open fun onSaverShown() {}

    private fun showSaver() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        onSaverShown()
        val d = resources.displayMetrics.density
        val textColor = Color.parseColor("#B3EEF3F6")
        saverTime = TextView(this).apply {
            textSize = 64f; setTextColor(textColor); typeface = android.graphics.Typeface.create("sans-serif-light", 0)
            setShadowLayer(12f, 0f, 2f, Color.BLACK)
        }
        saverDate = TextView(this).apply { textSize = 20f; setTextColor(textColor); setShadowLayer(10f, 0f, 2f, Color.BLACK) }
        // The current home-screen photo, darkened, behind the clock.
        saverPhoto = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.45f }
        saverCredit = TextView(this).apply { textSize = 10f; setTextColor(Color.parseColor("#80EEF3F6")) }
        showSaverPhoto(Backgrounds.current(this))
        lastPhotoChange = SystemClock.elapsedRealtime()
        saverBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(saverTime); addView(saverDate)
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(saverPhoto, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(saverCredit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = (12 * d).toInt(); bottomMargin = (6 * d).toInt()
            })
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
        // Change to the next stored photo every 10 minutes so no image stays on the panel for hours.
        if (SystemClock.elapsedRealtime() - lastPhotoChange > 10 * 60_000L) {
            lastPhotoChange = SystemClock.elapsedRealtime()
            showSaverPhoto(Backgrounds.next(this))
        }
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

    private lateinit var saverPhoto: ImageView
    private lateinit var saverCredit: TextView
    private var lastPhotoChange = 0L

    private fun showSaverPhoto(photo: Backgrounds.Photo?) {
        if (photo == null) { saverCredit.text = ""; return }
        saverPhoto.load(photo.file) { crossfade(1500) }
        saverCredit.text = if (photo.artist.isNotBlank()) getString(R.string.photo_credit, photo.artist) else "Wikimedia Commons"
    }

    /** Called after the screen saver closes (the home screen uses it to follow a changed photo). */
    protected open fun onSaverHidden() {}

    private fun hideSaver() {
        val v = saver ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        saver = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onSaverHidden()
    }
}
