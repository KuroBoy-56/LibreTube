package com.github.libretube.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.helpers.AlgoritmoHelper
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    val todosFeed: MutableLiveData<List<StreamItem>> = MutableLiveData()
    val subsFeed: MutableLiveData<List<StreamItem>> = MutableLiveData()
    val categoryFeed: MutableLiveData<List<StreamItem>> = MutableLiveData()
    val customFeed: MutableLiveData<List<StreamItem>> = MutableLiveData()
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)

    private var loadJob: Job? = null

    private val hideWatched get() = PreferenceHelper.getBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, false)
    private val showUpcoming get() = PreferenceHelper.getBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, true)

    fun loadTodos(context: Context, subscriptionsViewModel: SubscriptionsViewModel) {
        isLoading.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val subs = try { tryLoadFeed(subscriptionsViewModel) } catch (e: Exception) { emptyList() }

                var mixVideos = emptyList<StreamItem>()
                try {
                    val fallbackCat = listOf(TrendingCategory.MUSIC, TrendingCategory.GAMING, TrendingCategory.TRAILERS).random()
                    mixVideos = MediaServiceRepository.instance.getTrending("US", fallbackCat)
                } catch (e: Exception) {}

                if (mixVideos.isEmpty()) {
                    try {
                        val busquedaSegura = listOf("videos populares", "lo mas nuevo").random()
                        var result = try {
                            MediaServiceRepository.instance.getSearchResults(busquedaSegura, "videos")
                        } catch (e: Exception) {
                            MediaServiceRepository.instance.getSearchResults(busquedaSegura, "")
                        }
                        mixVideos = result.items.filterIsInstance<StreamItem>()
                    } catch (e: Exception) {}
                }

                val combined = (subs + mixVideos).distinctBy { it.url }.shuffled()
                todosFeed.postValue(combined)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                todosFeed.postValue(emptyList())
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    fun loadSubscriptionsOnly(subscriptionsViewModel: SubscriptionsViewModel) {
        isLoading.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val subs = tryLoadFeed(subscriptionsViewModel).shuffled()
                subsFeed.postValue(subs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                subsFeed.postValue(emptyList())
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    fun loadOfficialCategory(context: Context, category: TrendingCategory) {
        isLoading.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var videos = emptyList<StreamItem>()

                if (category == TrendingCategory.DEFAULT) {
                    // Intento 1: Buscar lo más nuevo (Variables renombradas para eliminar rastro)
                    try {
                        val queryNuevos = listOf("lo mas nuevo", "ultimos videos subidos", "videos virales").random()
                        var result = try {
                            MediaServiceRepository.instance.getSearchResults(queryNuevos, "videos")
                        } catch (e: Exception) {
                            MediaServiceRepository.instance.getSearchResults(queryNuevos, "")
                        }
                        videos = result.items.filterIsInstance<StreamItem>()
                    } catch (e: Exception) {}

                    // PARACAÍDAS: Si el buscador de Piped falla, forzamos cargar Música para no quedar en blanco
                    if (videos.isEmpty()) {
                        try { videos = MediaServiceRepository.instance.getTrending("US", TrendingCategory.MUSIC) } catch (e: Exception) {}
                    }
                } else {
                    // Resto de categorías oficiales
                    try {
                        val region = PreferenceHelper.getTrendingRegion(context)
                        videos = MediaServiceRepository.instance.getTrending(region, category)
                    } catch (e: Exception) {}

                    if (videos.isEmpty()) {
                        try { videos = MediaServiceRepository.instance.getTrending("US", category) } catch (e: Exception) {}
                    }
                }

                categoryFeed.postValue(videos)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                categoryFeed.postValue(emptyList())
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    fun loadCustomCategoryFallback(context: Context, query: String) {
        isLoading.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var videos = emptyList<StreamItem>()

                if (query.equals("Sugerencias para ti", ignoreCase = true)) {
                    try {
                        val recomendacion = AlgoritmoHelper.obtenerRecomendacion(context)

                        // CIRUGÍA: Se eliminó la etiqueta 'tendencias' de las validaciones
                        val searchQuery = if (recomendacion.isNotBlank() && recomendacion != "Unknown" && recomendacion != "default") {
                            recomendacion
                        } else {
                            "videos populares"
                        }

                        var result = try {
                            MediaServiceRepository.instance.getSearchResults(searchQuery, "videos")
                        } catch (e: Exception) {
                            MediaServiceRepository.instance.getSearchResults(searchQuery, "")
                        }
                        videos = result.items.filterIsInstance<StreamItem>()
                    } catch (e: Exception) {}

                    // PARACAÍDAS: Si el algoritmo falla o el servidor bloquea la búsqueda, forzamos GAMING
                    if (videos.isEmpty()) {
                        try { videos = MediaServiceRepository.instance.getTrending("US", TrendingCategory.GAMING) } catch (e: Exception) {}
                    }

                } else {
                    val safeQuery = when(query) {
                        "Música Asiática" -> listOf("asian pop music", "kpop hits", "jpop trending", "anime openings").random()
                        "Acción y Películas" -> listOf("action movies trailers", "peliculas de accion completas", "mejores escenas de accion").random()
                        "Estilo de Vida" -> listOf("lifestyle vlogs", "rutina de mañana vlog", "day in the life vlog").random()
                        else -> query
                    }

                    var result = try {
                        MediaServiceRepository.instance.getSearchResults(safeQuery, "videos")
                    } catch (e: Exception) {
                        MediaServiceRepository.instance.getSearchResults(safeQuery, "")
                    }

                    videos = result.items.filterIsInstance<StreamItem>()

                    if (videos.isEmpty()) {
                        val fallbackCat = if (query.contains("Música")) TrendingCategory.MUSIC else TrendingCategory.TRAILERS
                        try { videos = MediaServiceRepository.instance.getTrending("US", fallbackCat) } catch (e: Exception) {}
                    }
                }

                customFeed.postValue(videos.shuffled().take(25))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                customFeed.postValue(emptyList())
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        var currentFeed = subscriptionsViewModel.videoFeed.value
        if (currentFeed.isNullOrEmpty()) {
            currentFeed = SubscriptionHelper.getFeed(forceRefresh = true)
            subscriptionsViewModel.videoFeed.postValue(currentFeed)
        }
        return DatabaseHelper.filterByStreamTypeAndWatchPosition(currentFeed ?: emptyList(), hideWatched, showUpcoming)
    }
}