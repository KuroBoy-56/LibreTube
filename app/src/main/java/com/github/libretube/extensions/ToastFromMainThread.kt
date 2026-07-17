package com.github.libretube.extensions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.github.libretube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Context.toastFromMainThread(text: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_SHORT
        ).show()
    }
}

fun Context.showCustomDownloadToast() {
    Handler(Looper.getMainLooper()).post {
        val inflater = LayoutInflater.from(this)
        val layout = inflater.inflate(R.layout.layout_custom_toast, null)
        
        val toast = Toast(this)
        toast.duration = Toast.LENGTH_SHORT
        toast.view = layout
        toast.show()
        
        // Note: Toast.LENGTH_SHORT is usually ~2 seconds. 
        // We can't strictly force exactly 1.5s via standard Toast API without a custom overlay,
        // but this provides the requested look and feel.
    }
}

fun Context.toastFromMainThread(stringId: Int) {
    toastFromMainThread(getString(stringId))
}

suspend fun Context.toastFromMainDispatcher(text: String, length: Int = Toast.LENGTH_SHORT) {
    withContext(Dispatchers.Main) {
        Toast.makeText(this@toastFromMainDispatcher, text, length).show()
    }
}

suspend fun Context.toastFromMainDispatcher(stringId: Int, length: Int = Toast.LENGTH_SHORT) {
    toastFromMainDispatcher(getString(stringId), length)
}
