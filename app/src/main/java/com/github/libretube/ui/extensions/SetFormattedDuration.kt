package com.github.libretube.ui.extensions

import android.text.format.DateUtils
import android.widget.TextView
import com.github.libretube.R

fun TextView.setFormattedDuration(duration: Long, isShort: Boolean?, uploadDate: Long) {
    // Si la duración es irracionalmente grande (ej. > 1,000,000), probablemente esté en ms
    // Pero si es extremadamente grande (ej. > 1,000,000,000), es basura o error
    val safeDuration = when {
        duration > 1_000_000_000L -> -1L // Basura, mostrar En vivo o nada
        duration > 1_000_000L -> duration / 1000L // Probablemente ms
        else -> duration
    }
    
    this.text = when {
        isShort == true -> context.getString(R.string.yt_shorts)
        safeDuration < 0L -> context.getString(R.string.live)
        uploadDate > System.currentTimeMillis() -> context.getString(R.string.upcoming)
        else -> try {
            DateUtils.formatElapsedTime(safeDuration)
        } catch (_: Exception) {
            "--:--"
        }
    }
}
