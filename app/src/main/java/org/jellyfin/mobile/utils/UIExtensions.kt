@file:Suppress("unused", "NOTHING_TO_INLINE")

package org.jellyfin.mobile.utils

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import org.jellyfin.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

inline fun Context.toast(@StringRes text: Int, duration: Int = Toast.LENGTH_SHORT) =
    styledToast(getString(text), duration)

inline fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) =
    styledToast(text, duration)

/**
 * Toast with a rounded, semi-transparent dark background and white text,
 * so it stays readable over any (video) content. Uses a custom view — fine
 * because every toast in this app is shown while the app is in the
 * foreground (custom toasts are only restricted in the background).
 */
fun Context.styledToast(text: CharSequence, duration: Int) {
    val density = resources.displayMetrics.density
    val view = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        val horizontal = (22 * density).toInt()
        val vertical = (14 * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        background = ContextCompat.getDrawable(this@styledToast, R.drawable.bg_hint)
    }
    @Suppress("DEPRECATION")
    Toast(this).apply {
        setDuration(duration)
        setView(view)
        show()
    }
}

inline fun LifecycleOwner.runOnUiThread(noinline block: suspend CoroutineScope.() -> Unit) {
    lifecycleScope.launch(Dispatchers.Main, block = block)
}

fun LayoutInflater.withThemedContext(context: Context, @StyleRes style: Int): LayoutInflater {
    return cloneInContext(ContextThemeWrapper(context, style))
}

fun View.applyWindowInsetsAsMargins() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        updateLayoutParams<MarginLayoutParams> {
            setMargins(insets.left, insets.top, insets.right, insets.bottom)
        }
        windowInsets
    }
}

fun View.fadeIn() {
    alpha = 0f
    isVisible = true
    animate().apply {
        alpha(1f)
        @Suppress("MagicNumber")
        duration = 300L
        interpolator = LinearOutSlowInInterpolator()
        withLayer()
    }
}

inline fun Resources.dip(px: Int) = (px * displayMetrics.density).toInt()

inline var Window.brightness: Float
    get() = attributes.screenBrightness
    set(value) {
        attributes = attributes.apply {
            screenBrightness = value
        }
    }
