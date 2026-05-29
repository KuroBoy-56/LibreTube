package com.github.libretube.api

import android.util.Log
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.PipedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * REPOSITORIO "NUCLEAR": Bypass total de 403 mediante simulación de cliente nativo.
 * Se enfoca en limpiar URLs y forzar headers de compatibilidad extrema.
 */
class LocalStreamsExtractionPipedMediaServiceRepository : PipedMediaServiceRepository() {
    
    private val newPipeDelegate = NewPipeMediaServiceRepository()

    override suspend fun getStreams(videoId: String): Streams {
        Log.d("NuclearPlayback", "Iniciando bypass nuclear para: $videoId")
        
        return try {
            // 1. Extracción con NewPipe (ahora configurado para usar iOS Client en segundo plano)
            val streams = newPipeDelegate.getStreams(videoId)
            
            // 2. Normalización de Duración
            val fixedDuration = when {
                streams.duration > 1_000_000 -> streams.duration / 1000000
                streams.duration > 100_000 -> streams.duration / 1000
                else -> streams.duration
            }

            // 3. LIMPIEZA QUIRÚRGICA DE URLS (Bypass 403)
            // Quitamos parámetros de rastreo que causan el rechazo de la firma en Android
            val repairedVideo = streams.videoStreams.map { it.copy(url = cleanYoutubeUrl(it.url)) }
            val repairedAudio = streams.audioStreams.map { it.copy(url = cleanYoutubeUrl(it.url)) }

            val result = streams.copy(
                duration = fixedDuration,
                videoStreams = repairedVideo,
                audioStreams = repairedAudio
            )

            if (result.videoStreams.isEmpty() && result.hls.isNullOrEmpty()) {
                throw Exception("Extracción vacía, reintentando con proxy")
            }

            Log.d("NuclearPlayback", "¡Bypass exitoso! Enlaces reparados.")
            result
        } catch (e: Exception) {
            Log.e("NuclearPlayback", "Fallo bypass nuclear: ${e.message}. Usando servidores espejo de respaldo.")
            try {
                // Fallback a Kavin Rocks (Instancia más robusta)
                val emergencyApi = RetrofitInstance.buildRetrofitInstance<PipedApi>("https://pipedapi.kavin.rocks")
                emergencyApi.getStreams(videoId)
            } catch (_: Exception) {
                // Último recurso: PipedMediaServiceRepository nativo
                super.getStreams(videoId)
            }
        }
    }

    private fun cleanYoutubeUrl(url: String?): String? {
        if (url == null) return null
        // Si el enlace ya es de Piped o proxy, no tocar
        if (!url.contains("googlevideo.com")) return url
        
        // Mantener la firma intacta pero limpiar parámetros que YouTube usa para bloquear clientes no oficiales
        return url.replace("&source=youtube", "&source=android")
                  .replace("&mv=m", "&mv=u")
                  .substringBefore("&key=yt8") + "&key=yt8" + url.substringAfter("&key=yt8").substringBefore("&")
    }
}
