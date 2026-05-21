package com.github.libretube.ui.models

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsViewModel : ViewModel() {
    var videoFeed = MutableLiveData<List<StreamItem>?>()

    var subscriptions = MutableLiveData<List<Subscription>?>()
    val feedProgress = MutableLiveData<FeedProgress?>()

    var subFeedRecyclerViewState: Parcelable? = null

    fun fetchFeed(context: Context, forceRefresh: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var videoFeed = SubscriptionHelper.getFeed(forceRefresh = forceRefresh) { feedProgress ->
                    this@SubscriptionsViewModel.feedProgress.postValue(feedProgress)
                }
                
                // --- FALLBACK DE BÚSQUEDA SI EL FEED ESTÁ VACÍO ---
                if (videoFeed.isEmpty()) {
                    try {
                        val subscriptions = SubscriptionHelper.getSubscriptions()
                        if (subscriptions.isNotEmpty()) {
                            val fallbackList = mutableListOf<StreamItem>()
                            // Intentamos obtener los últimos vídeos de los canales suscritos
                            // Iteramos sobre los canales para recolectar un total de 50 videos
                            for (sub in subscriptions) {
                                if (fallbackList.size >= 50) break
                                try {
                                    val searchResult = MediaServiceRepository.instance.getSearchResults(sub.name, "videos")
                                    val items = searchResult.items
                                        .filter { it.type == "stream" }
                                        .map { it.toStreamItem() }
                                        .take(10) // Tomamos hasta 10 por canal para diversidad
                                    fallbackList.addAll(items)
                                } catch (e: Exception) { /* Ignorar error por canal individual */ }
                            }
                            if (fallbackList.isNotEmpty()) {
                                videoFeed = fallbackList
                                    .sortedByDescending { it.uploaded }
                                    .distinctBy { it.url } // Evitar duplicados
                                    .take(50) // Limitar a los 50 más recientes en total
                            }
                        }
                    } catch (e: Exception) { /* Silently fail search fallback */ }
                } else {
                    // Ordenar el feed normal también de más reciente a más antiguo
                    videoFeed = videoFeed.sortedByDescending { it.uploaded }
                }

                this@SubscriptionsViewModel.videoFeed.postValue(videoFeed)
                videoFeed.firstOrNull { !it.isUpcoming }?.uploaded?.let {
                    PreferenceHelper.updateLastFeedWatchedTime(it, false)
                }
            } catch (e: Exception) {
                // Notificar error pero limpiar el estado de progreso para evitar carga infinita
                this@SubscriptionsViewModel.feedProgress.postValue(null)
                context.toastFromMainDispatcher(R.string.server_error)
                Log.e(TAG(), e.toString())
            }
        }
    }

    fun fetchSubscriptions(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val subscriptions = try {
                SubscriptionHelper.getSubscriptions()
            } catch (e: Exception) {
                context.toastFromMainDispatcher(R.string.server_error)
                Log.e(TAG(), e.toString())
                return@launch
            }
            this@SubscriptionsViewModel.subscriptions.postValue(subscriptions)
        }
    }
}
