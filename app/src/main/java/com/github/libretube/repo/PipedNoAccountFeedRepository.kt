package com.github.libretube.repo

import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.SubscriptionHelper.GET_SUBSCRIPTIONS_LIMIT
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.PreferenceHelper

class PipedNoAccountFeedRepository : FeedRepository {
    private suspend fun <T> runWithFallback(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(4) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                PreferenceHelper.rotateInstance()
                RetrofitInstance.resetApi()
            }
        }
        throw lastException ?: Exception("Error en PipedNoAccountFeedRepository")
    }

    override suspend fun getFeed(
        forceRefresh: Boolean,
        onProgressUpdate: (FeedProgress) -> Unit
    ): List<StreamItem> = runWithFallback {
        val channelIds = SubscriptionHelper.getSubscriptionChannelIds()

        when {
            channelIds.size > GET_SUBSCRIPTIONS_LIMIT ->
                RetrofitInstance.authApi
                    .getUnauthenticatedFeed(channelIds)

            else -> RetrofitInstance.authApi.getUnauthenticatedFeed(
                channelIds.joinToString(",")
            )
        }
    }
}