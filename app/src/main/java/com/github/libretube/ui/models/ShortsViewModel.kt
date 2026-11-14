package com.github.libretube.ui.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.ShortItem
import com.github.libretube.api.ShortsRepository
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

    /** Los cuatro Shorts de la fila del inicio (con título). */
    private val _shelf = MutableLiveData<List<ShortItem>>(emptyList())
    val shelf: LiveData<List<ShortItem>> = _shelf

    /** Posición pedida al abrir un Short desde el inicio (se consume una vez). */
    var pendingStartIndex: Int? = null

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private var nextToken: String? = null
    private var loadJob: Job? = null
    private var shelfJob: Job? = null
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

    /** Fila de Shorts del inicio: 4 Shorts con su título, como la app de YouTube. */
    fun loadShelf(force: Boolean = false) {
        if (!force && (!_shelf.value.isNullOrEmpty() || shelfJob?.isActive == true)) return
        shelfJob = viewModelScope.launch {
            runCatching {
                val page = ShortsRepository.loadAtLeast(SHELF_SIZE)
                val items = page.items.take(SHELF_SIZE)
                ShortsRepository.fillTitles(items)
                items
            }.onSuccess { _shelf.value = it }
                .onFailure { Log.e(TAG, "shelf", it) }
        }
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
        const val SHELF_SIZE = 4
    }
}
