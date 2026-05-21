package com.github.libretube.ui.tools

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService

object SleepTimer {
    // Mantiene el tiempo para que el menú de la app lo lea sin problemas
    var timeLeftMillis: Long = 0L
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var countdownTask: Runnable? = null

    fun start(context: Context, delayInMinutes: Long) {
        stop(context)
        if (delayInMinutes <= 0L) return

        // Contexto seguro que no muere cuando se apaga la pantalla
        val appContext = context.applicationContext
        timeLeftMillis = delayInMinutes * DateUtils.MINUTE_IN_MILLIS

        countdownTask = object : Runnable {
            override fun run() {
                timeLeftMillis -= 1000L

                if (timeLeftMillis <= 0) {
                    // LLEGÓ A CERO: Pausa la música y termina
                    stopMusic(appContext)
                    stop(appContext)
                } else {
                    // Sigue contando 1 segundo a la vez
                    handler.postDelayed(this, 1000L)
                }
            }
        }

        handler.postDelayed(countdownTask!!, 1000L)
    }

    fun stop(context: Context) {
        countdownTask?.let { handler.removeCallbacks(it) }
        countdownTask = null
        timeLeftMillis = 0L
    }

    private fun stopMusic(appContext: Context) {
        // Utilizamos exactamente el método de MediaController que SÍ te funcionó para pausar.
        // Como eliminamos el código del volumen, el reproductor simplemente se pausará
        // y conservará su volumen normal para cuando reproduzcas otra cosa.
        try {
            // 1. Conectar al reproductor ONLINE y pausarlo
            val onlineToken = SessionToken(appContext, ComponentName(appContext, OnlinePlayerService::class.java))
            val onlineFuture = MediaController.Builder(appContext, onlineToken).buildAsync()

            onlineFuture.addListener({
                try {
                    val controller = onlineFuture.get()
                    controller.pause() // Pone pausa limpiamente
                    controller.release() // Se desconecta para no gastar memoria
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(appContext))

            // 2. Conectar al reproductor OFFLINE y pausarlo
            val offlineToken = SessionToken(appContext, ComponentName(appContext, OfflinePlayerService::class.java))
            val offlineFuture = MediaController.Builder(appContext, offlineToken).buildAsync()

            offlineFuture.addListener({
                try {
                    val controller = offlineFuture.get()
                    controller.pause() // Pone pausa limpiamente
                    controller.release() // Se desconecta para no gastar memoria
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(appContext))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}