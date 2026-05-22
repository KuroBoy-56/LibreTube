package com.github.libretube.ui.extensions

import android.text.format.DateUtils
import android.widget.TextView
import com.github.libretube.R

fun TextView.setFormattedDuration(duration: Long, isShort: Boolean?, uploadDate: Long) {
    // FILTRO ANTICUTRE: Bloqueo de cifras absurdas de 10 números
    // Si la duración > 24h (86400s), probablemente sean datos basura de un enlace roto
    val safeDuration = when {
        duration > 86400L -> -1L // Marcar como basura/en vivo
        duration < 0L -> -1L
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
