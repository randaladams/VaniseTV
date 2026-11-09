package com.vanisetv.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.dispose
import coil.load
import com.vanisetv.app.R
import com.vanisetv.app.data.Channel

private val PALETTE = intArrayOf(
    0xFF1F6FB2.toInt(), 0xFFB23A3A.toInt(), 0xFF2F8A5B.toInt(), 0xFF8A4FB2.toInt(),
    0xFFC27A1A.toInt(), 0xFF2A8A8A.toInt(), 0xFFA83F7A.toInt(), 0xFF4D6B2F.toInt(),
)

fun initials(name: String): String =
    name.split(' ', '-', '+').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }

/**
 * Fills a logo box (layout view_logo): the channel's logo when it loads, otherwise
 * coloured initials. Logos are cached on disk by Coil, so each downloads once.
 */
fun bindLogo(root: View, channel: Channel?) {
    val text = root.findViewById<TextView>(R.id.logoText)
    val image = root.findViewById<ImageView>(R.id.logoImage)
    image.dispose()
    image.setImageDrawable(null)
    if (channel == null) {
        text.text = ""
        root.background = null
        return
    }
    val color = PALETTE[Math.floorMod(channel.name.hashCode(), PALETTE.size)]
    text.text = initials(channel.name)
    text.visibility = View.VISIBLE
    root.background = GradientDrawable().apply { cornerRadius = 8 * root.resources.displayMetrics.density; setColor(color) }
    if (channel.logo.isBlank()) return
    image.load(channel.logo) {
        size(256, 256)
        listener(onSuccess = { _, _ ->
            text.visibility = View.GONE
            root.background = GradientDrawable().apply { cornerRadius = 8 * root.resources.displayMetrics.density; setColor(Color.parseColor("#F2F5F7")) }
        })
    }
}

/** Slight zoom on focus for tiles. */
fun View.scaleOnFocus(scale: Float = 1.04f) {
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun formatWatched(context: Context, seconds: Long): String {
    val hours = seconds / 3600
    return if (hours >= 1) context.getString(R.string.hours_short, hours.toInt())
    else context.getString(R.string.minutes_short, (seconds / 60).toInt().coerceAtLeast(1))
}
