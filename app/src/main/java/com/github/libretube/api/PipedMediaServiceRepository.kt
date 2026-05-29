package com.github.libretube.api

import android.util.Log
import com.github.libretube.api.RetrofitInstance.PIPED_API_URL
import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

open class PipedMediaServiceRepository : MediaServiceRepository {
    override fun getTrendingCategories(): List<TrendingCategory> = emptyList()

    private suspend fun <T> runWithFallback(block: suspend () -> T): T {
        var lastException: Exception? = null
        val maxRetries = 6
        repeat(maxRetries) { attempt ->
            try {
                val result = block()
                
                // VERIFICACIÓN FÍSICA RELÁMPAGO (HEAD Request)
                // Solo para extracción de videos: evita entregar enlaces muertos (403/404)
                if (result is Streams) {
                    val urlToVerify = result.hls ?: result.videoStreams.firstOrNull()?.url
                    if (urlToVerify != null) {
                        val isAlive = withContext(Dispatchers.IO) {
                            try {
                                val conn = java.net.URL(urlToVerify).openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "HEAD"
                                conn.connectTimeout = 1200 // Muy rápido para evitar carga infinita
                                conn.readTimeout = 1200
                                conn.responseCode in 200..399
                            } catch (_: Exception) { false }
                        }
                        if (!isAlive) throw Exception("Enlace inestable")
                    }
                }
                
                return result
            } catch (e: Exception) {
                lastException = e
                PreferenceHelper.rotateInstance()
                RetrofitInstance.resetApi()
                // Pausa mínima para refrescar conexión
                if (attempt < maxRetries - 1) delay(50) 
            }
        }
        throw lastException ?: Exception("Servidores de la comunidad no responden")
    }

    override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> = runWithFallback {
        api.getTrending(region)
    }

    override suspend fun getStreams(videoId: String): Streams = runWithFallback {
        api.getStreams(videoId).also {
            it.isShort = it.videoStreams.firstOrNull()?.let { stream ->
                (stream.height ?: 0) > (stream.width ?: 0)
            } ?: false
        }
    }

    override suspend fun getComments(videoId: String): CommentsPage = runWithFallback {
        api.getComments(videoId)
    }

    override suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>?
    ): SegmentData = runWithFallback {
        api.getSegments(
            videoId,
            JsonHelper.json.encodeToString(category),
            JsonHelper.json.encodeToString(actionType)
        )
    }

    override suspend fun getDeArrowContent(videoId: String): DeArrowContent? = runWithFallback {
        api.getDeArrowContent(videoId)[videoId]
    }

    override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage = runWithFallback {
        api.getCommentsNextPage(videoId, nextPage)
    }

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult = runWithFallback {
        api.getSearchResults(searchQuery, filter)
    }

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult = runWithFallback {
        api.getSearchResultsNextPage(searchQuery, filter, nextPage)
    }

    override suspend fun getSuggestions(query: String): List<String> = runWithFallback {
        api.getSuggestions(query)
    }

    override suspend fun getChannel(channelId: String): Channel = runWithFallback {
        api.getChannel(channelId)
    }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse = runWithFallback {
        api.getChannelTab(data, nextPage)
    }

    override suspend fun getChannelByName(channelName: String): Channel = runWithFallback {
        api.getChannelByName(channelName)
    }

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel = runWithFallback {
        api.getChannelNextPage(channelId, nextPage)
    }

    override suspend fun getPlaylist(playlistId: String): Playlist = runWithFallback {
        api.getPlaylist(playlistId)
    }

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist = runWithFallback {
        api.getPlaylistNextPage(playlistId, nextPage)
    }

    companion object {
        val apiUrl get() = PreferenceHelper.getString(PreferenceKeys.FETCH_INSTANCE, PIPED_API_URL)

        private val api by resettableLazy(RetrofitInstance.apiLazyMgr) {
            RetrofitInstance.buildRetrofitInstance<PipedApi>(apiUrl)
        }
    }
}