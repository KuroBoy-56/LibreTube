package com.github.libretube.helpers

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.util.YoutubeHlsPlaylistParser

/**
 * La fuente de un Short con la misma elección que el reproductor principal
 * (`OnlinePlayerService.setStreamSource`, como en LibreTube original): SABR, si no DASH solo con
 * las pistas que no son SABR, y HLS al final.
 */
object ShortsMediaSources {
    @OptIn(UnstableApi::class)
    fun create(context: Context, videoId: String, streams: Streams): MediaSource? {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        return when {
            !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(streams.serverAbrStreamingUrl.toUri())
                    .setMimeType("application/vnd.yt-ump")
                    .build()
                SabrMediaSource.Factory(SabrManifest(videoId, streams)).createMediaSource(mediaItem)
            }

            !streams.isLive && streams.videoStreams.any { it.url?.startsWith("sabr://") != true } -> {
                val dashUri = PlayerHelper.createDashSource(
                    streams.copy(videoStreams = streams.videoStreams.filter {
                        it.url?.startsWith("sabr://") != true
                    }),
                    context
                )
                val mediaItem = MediaItem.Builder()
                    .setUri(dashUri)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            !streams.hls.isNullOrBlank() -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(streams.hls.toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                HlsMediaSource.Factory(dataSourceFactory)
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                    .createMediaSource(mediaItem)
            }

            else -> null
        }
    }
}
