package com.github.libretube.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Un Short del feed: su id, su miniatura vertical y (cuando se conocen) su título y canal.
 */
data class ShortItem(
    val videoId: String,
    val thumbnail: String,
    var title: String? = null,
    var channel: String? = null
)

/**
 * El feed de Shorts tal como lo da la pestaña Shorts de YouTube, con la misma lógica que la app
 * de Roku (TizenTube Roku, `shortsFeed` en YouTubeTask.brs, y SmartTube `getShortsWeb`):
 *
 * - `reel/reel_item_watch` sin semilla (`REEL_WATCH_INPUT_TYPE_SEEDLESS`) da el primer Short y
 *   el token de la secuencia.
 * - `reel/reel_watch_sequence` con ese token da los siguientes (unos 8 a 14 por página) y el token
 *   de la página siguiente.
 *
 * Solo llegan ids y miniaturas verticales: el título y el canal salen del propio video (o de
 * oEmbed para los mosaicos del inicio). Los Shorts que YouTube mete como anuncio se descartan.
 */
object ShortsRepository {
    private const val TAG = "ShortsRepository"
    private const val BASE_URL = "https://www.youtube.com/youtubei/v1/"
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_ID = "1"
    private const val CLIENT_VERSION = "2.20260907.06.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val jsonType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Identificador de visitante que YouTube da en la primera respuesta (como una cookie). */
    @Volatile
    private var visitorData: String? = null

    /** Una página del feed: los Shorts y el token de la siguiente (null = no hay más). */
    data class Page(val items: List<ShortItem>, val nextToken: String?)

    /** Primera página: el primer Short y la secuencia (la carga [nextPage] con el token). */
    suspend fun firstPage(): Page = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("disablePlayerResponse", true)
            .put("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
            .put("params", "CA8=")
        val json = post("reel/reel_item_watch", payload)
        val items = mutableListOf<ShortItem>()
        json.optJSONObject("replacementEndpoint")?.optJSONObject("reelWatchEndpoint")?.let {
            reelToItem(it)?.let(items::add)
        }
        val token = json.optString("sequenceContinuation").ifBlank { null }
        Page(items, token)
    }

    /** Página siguiente de la secuencia. */
    suspend fun nextPage(token: String): Page = withContext(Dispatchers.IO) {
        val json = post("reel/reel_watch_sequence", JSONObject().put("sequenceParams", token))
        val items = mutableListOf<ShortItem>()
        val entries = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (isAd(entry)) continue
            entry.optJSONObject("command")?.optJSONObject("reelWatchEndpoint")?.let {
                reelToItem(it)?.let(items::add)
            }
        }
        val next = json.optString("continuation").ifBlank {
            json.optJSONObject("continuationEndpoint")
                ?.optJSONObject("continuationCommand")
                ?.optString("token")
                .orEmpty()
        }.ifBlank { null }
        Page(items, next)
    }

    /**
     * Al menos [count] Shorts (la primera página y las que hagan falta), para la fila del inicio.
     */
    suspend fun loadAtLeast(count: Int, maxPages: Int = 3): Page {
        val first = firstPage()
        val items = first.items.toMutableList()
        var token = first.nextToken
        var pages = 0
        while (items.size < count && token != null && pages < maxPages) {
            val page = nextPage(token)
            page.items.forEach { item -> if (items.none { it.videoId == item.videoId }) items.add(item) }
            token = page.nextToken
            pages++
        }
        return Page(items, token)
    }

    /**
     * Título y canal de cada Short sin título (oEmbed de YouTube, una consulta liviana por video),
     * para los mosaicos del inicio, que muestran el título como la app original.
     */
    suspend fun fillTitles(items: List<ShortItem>) = withContext(Dispatchers.IO) {
        coroutineScope {
            items.filter { it.title.isNullOrBlank() }.map { item ->
                async {
                    runCatching {
                        val url = "https://www.youtube.com/oembed?format=json&url=" +
                            "https://www.youtube.com/shorts/${item.videoId}"
                        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use
                            val json = JSONObject(response.body.string())
                            item.title = json.optString("title").ifBlank { null }
                            item.channel = json.optString("author_name").ifBlank { null }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun reelToItem(reel: JSONObject): ShortItem? {
        if (isAd(reel)) return null
        val videoId = reel.optString("videoId").ifBlank { return null }
        val thumbnails = reel.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        var best: String? = null
        var bestWidth = -1
        if (thumbnails != null) {
            for (i in 0 until thumbnails.length()) {
                val t = thumbnails.optJSONObject(i) ?: continue
                val width = t.optInt("width", 0)
                if (width > bestWidth) {
                    bestWidth = width
                    best = t.optString("url")
                }
            }
        }
        val thumbnail = best?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?: "https://i.ytimg.com/vi/$videoId/oardefault.jpg"
        return ShortItem(videoId, thumbnail)
    }

    /** Entradas que son anuncios (como las descarta TizenTube y la app de Roku). */
    private fun isAd(node: JSONObject): Boolean {
        val text = node.toString().lowercase()
        return text.contains("\"isad\":true") || text.contains("adslotrenderer")
    }

    private fun post(endpoint: String, payload: JSONObject): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", java.util.Locale.getDefault().language.ifBlank { "es" })
            .put("gl", java.util.Locale.getDefault().country.ifBlank { "US" })
        visitorData?.let { clientContext.put("visitorData", it) }
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
        payload.keys().forEach { key -> body.put(key, payload.get(key)) }

        val request = Request.Builder()
            .url("$BASE_URL$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(jsonType))
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", CLIENT_ID)
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "$endpoint: HTTP ${response.code}")
                throw java.io.IOException("$endpoint: HTTP ${response.code}")
            }
            val json = JSONObject(response.body.string())
            json.optJSONObject("responseContext")?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { visitorData = it }
            return json
        }
    }
}
