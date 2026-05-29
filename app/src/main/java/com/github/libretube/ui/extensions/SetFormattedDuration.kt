package com.github.libretube.ui.extensions

import android.text.format.DateUtils
import android.widget.TextView
import com.github.libretube.R

fun TextView.setFormattedDuration(duration: Long, isShort: Boolean?, uploadDate: Long) {
    // FILTRO RADICAL ANTI-CIFRAS GIGANTES (10 NÚMEROS)
    // Cualquier duración superior a 24 horas (86400 segundos) es basura o error
    val safeDuration = if (duration > 86400L || duration < 0L) {
        -1L // Forzar estado En vivo o Limpio
    } else {
        duration
    }
    
    this.text = when {
        isShort == true -> context.getString(R.string.yt_shorts)
        safeDuration < 0L -> context.getString(R.string.live)
        uploadDate > System.currentTimeMillis() -> context.getString(R.string.upcoming)
        else -> try {
            DateUtils.formatElapsedTime(safeDuration)
        } catch (_: Exception) {
            "00:00"
        }
    }
}
