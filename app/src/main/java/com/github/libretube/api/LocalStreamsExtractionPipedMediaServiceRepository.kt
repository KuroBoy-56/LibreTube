package com.github.libretube.api

import com.github.libretube.api.obj.Streams
import kotlinx.coroutines.delay

class LocalStreamsExtractionPipedMediaServiceRepository: PipedMediaServiceRepository() {
    private val newPipeDelegate = NewPipeMediaServiceRepository()

    override suspend fun getStreams(videoId: String): Streams {
        var lastException: Exception? = null
        
        // FASE 1: Extracción Local Pura (Estilo Vanced/NewPipe)
        // Intentar hasta 2 veces localmente para filtrar errores de red temporales
        repeat(2) { attempt ->
            try {
                val streams = newPipeDelegate.getStreams(videoId)
                // Verificación agresiva de integridad de los enlaces obtenidos
                if (streams.videoStreams.isNotEmpty() || streams.hls != null) {
                    return streams
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == 0) delay(300) // Pausa corta antes del segundo intento local
            }
        }

        // FASE 2: Fallback a Infraestructura Piped Global (con rotación interna)
        // Si la extracción local falla (ej. cambio en el algoritmo de YT), saltamos a los servidores
        try {
            return super.getStreams(videoId)
        } catch (e: Exception) {
            lastException = e
        }

        throw lastException ?: Exception("Error crítico: Imposible obtener enlaces de video")
    }
}
