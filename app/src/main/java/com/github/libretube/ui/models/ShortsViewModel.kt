package com.github.libretube.ui.models

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.ShortItem
import com.github.libretube.api.ShortsRepository
import com.github.libretube.helpers.AlgoritmoHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Estado de los Shorts, compartido por la fila de Shorts del inicio y la sección Shorts.
 *
 * Como en la app de Roku: al abrir un Short desde el inicio, primero siguen los de esa fila (los
 * de antes quedan arriba) y después el feed de Shorts de YouTube, que se carga antes de que se
 * acaben. Nunca sigue un video horizontal.
 */
class ShortsViewModel : ViewModel() {
    private val _shorts = MutableLiveData<List<ShortItem>>(emptyList())
    val shorts: LiveData<List<ShortItem>> = _shorts

    /**
     * Shorts de las filas del Inicio para la pestaña que se cargó (con título). [key] identifica
     * cada carga: el Inicio solo usa la que pidió por última vez.
     */
    data class HomeShorts(val key: Int, val items: List<ShortItem>)

    private val _homeShorts = MutableLiveData(HomeShorts(0, emptyList()))
    val homeShorts: LiveData<HomeShorts> = _homeShorts

    /** Posición pedida al abrir un Short desde el inicio (se consume una vez). */
    var pendingStartIndex: Int? = null

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private var nextToken: String? = null
    private var loadJob: Job? = null
    private var homeJob: Job? = null
    private var homeKey = 0
    private var started = false

    /** Carga el feed la primera vez que se abre la sección. */
    fun ensureFeed() {
        if (started) return
        started = true
        loadJob = viewModelScope.launch {
            runCatching { ShortsRepository.firstPage() }
                .onSuccess { page ->
                    nextToken = page.nextToken
                    append(page.items)
                    // El primer Short ya se puede ver; la secuencia llega mientras tanto.
                    loadMore()
                }
                .onFailure {
                    Log.e(TAG, "feed", it)
                    started = false
                    _error.value = it.message
                }
        }
    }

    /** Carga la página siguiente si quedan pocos Shorts por ver. */
    fun loadMoreIfNeeded(position: Int) {
        val size = _shorts.value.orEmpty().size
        if (position >= size - 4) loadMore()
    }

    fun loadMore() {
        if (loadJob?.isActive == true) return
        val token = nextToken
        if (token == null) {
            // Sin token (feed agotado o aún no cargado): se empieza otra secuencia.
            if (!started || _shorts.value.isNullOrEmpty()) return
            loadJob = viewModelScope.launch {
                runCatching { ShortsRepository.firstPage() }.onSuccess { page ->
                    nextToken = page.nextToken
                    append(page.items)
                }
            }
            return
        }
        loadJob = viewModelScope.launch {
            runCatching { ShortsRepository.nextPage(token) }
                .onSuccess { page ->
                    nextToken = page.nextToken
                    append(page.items)
                }
                .onFailure { Log.e(TAG, "nextPage", it) }
        }
    }

    /**
     * Shorts para las filas del Inicio de una pestaña, distintos en cada carga:
     * - [queries] null: el feed de la pestaña Shorts de YouTube (aleatorio en cada carga).
     * - si no, la búsqueda de Shorts de uno de esos temas (elegido al azar), varias páginas
     *   mezcladas; si no trae nada, el feed.
     * [personal]: primero la recomendación del panel para este usuario ("Sugerencias para ti").
     * Publica a medida que llegan (las primeras filas aparecen enseguida). Devuelve la clave de
     * esta carga.
     */
    fun loadHomeShorts(context: Context, queries: List<String>?, personal: Boolean, wanted: Int): Int {
        homeJob?.cancel()
        val key = ++homeKey
        _homeShorts.value = HomeShorts(key, emptyList())
        val appContext = context.applicationContext
        homeJob = viewModelScope.launch {
            val seen = HashSet<String>()
            var items = emptyList<ShortItem>()
            fun publish(newItems: List<ShortItem>) {
                val fresh = newItems.filter { seen.add(it.videoId) }
                if (fresh.isEmpty()) return
                items = items + fresh
                _homeShorts.value = HomeShorts(key, items)
            }

            var topics = queries
            if (personal) {
                val recommendation = runCatching {
                    AlgoritmoHelper.obtenerRecomendacion(appContext)
                }.getOrDefault("")
                if (recommendation.isNotBlank() && recommendation !in listOf("tendencias", "Unknown", "default")) {
                    topics = listOf(recommendation)
                }
            }

            if (topics != null) {
                var token: String? = null
                val query = topics.random()
                for (page in 0 until SEARCH_PAGES) {
                    val result = runCatching { ShortsRepository.search(query, token) }
                        .onFailure { Log.e(TAG, "home search", it) }
                        .getOrNull() ?: break
                    publish(result.items.shuffled())
                    token = result.nextToken ?: break
                    if (items.size >= wanted) break
                }
            }

            // El feed de Shorts: sin tema, o si la búsqueda no trajo nada.
            if (items.isEmpty()) {
                var token: String? = null
                for (page in 0 until FEED_PAGES) {
                    val result = runCatching {
                        if (token == null) ShortsRepository.firstPage() else ShortsRepository.nextPage(token!!)
                    }.onFailure { Log.e(TAG, "home feed", it) }.getOrNull() ?: break
                    // El feed no trae títulos: se piden antes de mostrar la fila.
                    runCatching { ShortsRepository.fillTitles(result.items) }
                    publish(result.items)
                    token = result.nextToken ?: break
                    if (items.size >= wanted) break
                }
            }
        }
        return key
    }

    /**
     * Abre la sección Shorts en [index] de [items] (la fila del inicio): esos Shorts primero y
     * después el feed.
     */
    fun startFrom(items: List<ShortItem>, index: Int) {
        val rest = _shorts.value.orEmpty().filter { s -> items.none { it.videoId == s.videoId } }
        _shorts.value = items + rest
        pendingStartIndex = index
        if (!started) {
            started = true
            loadJob = viewModelScope.launch {
                runCatching { ShortsRepository.firstPage() }.onSuccess { page ->
                    nextToken = page.nextToken
                    append(page.items)
                    loadMore()
                }
            }
        }
    }

    fun retry() {
        _error.value = null
        started = false
        ensureFeed()
    }

    private fun append(items: List<ShortItem>) {
        val current = _shorts.value.orEmpty()
        val known = current.map { it.videoId }.toHashSet()
        val fresh = items.filter { known.add(it.videoId) }
        if (fresh.isNotEmpty()) _shorts.value = current + fresh
    }

    companion object {
        private const val TAG = "ShortsViewModel"
        private const val SEARCH_PAGES = 3
        private const val FEED_PAGES = 12
    }
}
