package com.github.libretube.util

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

class CrashManager(
    private val appVersion: String,
    private val apiUrl: String,
    private val appName: String
) : Thread.UncaughtExceptionHandler {
    // Guardamos el manejador que LibreTube usa por defecto
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        val stackTrace = exception.stackTraceToString()
        val message = exception.message ?: "Sin mensaje"

        sendErrorToServer("FATAL_CRASH", message, stackTrace)

        // Llamamos al manejador original para que la app cierre correctamente
        defaultHandler?.uncaughtException(thread, exception) ?: exitProcess(1)
    }

    private fun sendErrorToServer(tipo: String, mensaje: String, stacktrace: String) {
        if (apiUrl.isEmpty()) return
        try {
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val json = JSONObject().apply {
                put("app_name", appName)
                put("tipo", tipo)
                put("mensaje", mensaje)
                put("stacktrace", stacktrace)
                put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                put("version_app", appVersion)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.e("CrashManager", "Fallo al enviar error al servidor: ${e.message}")
        }
    }

    companion object {
        private var instance: CrashManager? = null

        fun init(appVersion: String, apiUrl: String, appName: String) {
            instance = CrashManager(appVersion, apiUrl, appName)
            Thread.setDefaultUncaughtExceptionHandler(instance)
        }
    }
}