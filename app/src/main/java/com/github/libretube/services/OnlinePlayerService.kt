package com.github.libretube.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getSubtitleRoleFlags
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.util.DeArrowUtil
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YoutubeHlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class OnlinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false

    private var playlistId: String? = null
    private var channelId: String? = null
    private var startTimestampSeconds: Long? = null

    private var streams: Streams? = null

    // CIRUGÍA: SupervisorJob evita que un error destruya el reproductor entero
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var fetchVideoInfoJob: Job? = null
    private var retryCount = 0

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // CIRUGÍA: Interceptor para rescatar los En Vivo si se desincronizan
            if (error.cause is BehindLiveWindowException) {
                Log.w(TAG(), "BehindLiveWindowException: Resincronizando En Vivo...")
                exoPlayer?.seekToDefaultPosition()
                exoPlayer?.prepare()
                return
            }

            if (retryCount < 2) {
                retryCount++
                Log.w(TAG(), "Player error: ${error.errorCodeName}. Retrying ($retryCount/2)...")
                scope.launch {
                    startPlayback()
                }
            } else {
                if (com.github.libretube.helpers.NetworkHelper.isNetworkAvailable(this@OnlinePlayerService) &&
                    error.errorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                    toastFromMainThread(error.localizedMessage ?: "Unknown error")
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    if (!isTransitioning) playNextVideo()
                }

                Player.STATE_IDLE -> {
                    onDestroy()
                }

                Player.STATE_BUFFERING -> {}
                Player.STATE_READY -> {
                    if (PlayerHelper.watchHistoryEnabled) {
                        scope.launch(Dispatchers.IO) {
                            streams?.let { streams ->
                                val watchHistoryItem =
                                    streams.toStreamItem(videoId).toWatchHistoryItem(videoId)
                                DatabaseHelper.addToWatchHistory(watchHistoryItem)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun onServiceCreated(args: Bundle) {
        val playerData = args.parcelable<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly)

        videoId = playerData.videoId!!
        playlistId = playerData.playlistId
        channelId = playerData.channelId
        startTimestampSeconds = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        exoPlayer?.addListener(playerListener)
        trackSelector?.updateParameters {
            setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
        }
    }

    override suspend fun startPlayback() {
        super.startPlayback()

        val timestampMs = startTimestampSeconds?.times(1000) ?: 0L
        startTimestampSeconds = null

        fetchVideoInfoJob?.cancelAndJoin()

        fetchVideoInfoJob = scope.launch {
            streams = withContext(Dispatchers.IO) {
                try {
                    MediaServiceRepository.instance.getStreams(videoId).let {
                        DeArrowUtil.deArrowStreams(it, videoId)
                    }
                }  catch (e: Exception) {
                    Log.e(TAG(), e.stackTraceToString())
                    if (com.github.libretube.helpers.NetworkHelper.isNetworkAvailable(this@OnlinePlayerService) &&
                        e !is java.net.UnknownHostException) {
                        toastFromMainDispatcher(e.localizedMessage.orEmpty())
                    }
                    return@withContext null
                }
            } ?: return@launch

            streams?.toStreamItem(videoId)?.let {
                PlayingQueue.updateCurrent(it)

                if (!PlayingQueue.hasNext()) {
                    PlayingQueue.updateQueue(it, playlistId, channelId, streams!!.relatedStreams)
                }

                SubscriptionHelper.submitFeedItemChange(it.toFeedItem())
            }

            launch {
                val segments = getSponsorBlockSegments()
                withContext(Dispatchers.Main) { setSponsorBlockSegments(segments) }
            }

            withContext(Dispatchers.Main) {
                setStreamSource()
                configurePlayer(timestampMs)
            }
        }

        fetchVideoInfoJob?.join()
        fetchVideoInfoJob = null
    }

    private fun configurePlayer(seekToPositionMs: Long) {
        if (seekToPositionMs != 0L) {
            exoPlayer?.seekTo(seekToPositionMs)
        } else if (watchPositionsEnabled) {
            DatabaseHelper.getWatchPositionBlocking(videoId)?.let {
                if (!DatabaseHelper.isVideoWatched(it, streams?.duration)) exoPlayer?.seekTo(it)
            }
        }

        exoPlayer?.apply {
            playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            prepare()
        }
    }

    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null) {
            if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
                exoPlayer?.seekTo(0)
                return
            }

            if (!PlayerHelper.isAutoPlayEnabled(playlistId != null) || !shouldHandleAutoplay) return
        }

        val nextVideo = nextId ?: PlayingQueue.getNext() ?: return

        navigateVideo(nextVideo)
    }

    private suspend fun getSponsorBlockSegments(): List<Segment> {
        return runCatching {
            MediaServiceRepository.instance.getSegments(
                videoId,
                sponsorBlockConfig.keys.toList(),
                listOf("skip", "mute", "full", "poi", "chapter")
            ).segments
        }.getOrElse { emptyList() }
    }

    override fun navigateVideo(videoId: String) {
        this.streams = null
        this.retryCount = 0

        super.navigateVideo(videoId)
    }

    private fun setStreamSource() {
        val streams = streams ?: return

        // CIRUGÍA: Try-catch para evitar que un error de red o manifiesto tumbe el servicio
        try {
            when {
                // 1. VOD: Motor SABR (Solo videos normales)
                !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                    val sabrMediaSourceFactory = SabrMediaSource.Factory(
                        SabrManifest(videoId, streams)
                    )
                    val mediaItem = createMediaItem(
                        streams.serverAbrStreamingUrl.toUri(),
                        "application/vnd.yt-ump",
                        streams
                    )
                    val mediaSource = sabrMediaSourceFactory.createMediaSource(mediaItem)
                    val mediaSources = listOf<MediaSource>(mediaSource) + streams.subtitles.map {
                        val format = Format.Builder()
                            .setSampleMimeType(it.mimeType)
                            .setLanguage(it.code)
                            .setRoleFlags(getSubtitleRoleFlags(it))
                            .build()
                        val subtitleParserFactory = DefaultSubtitleParserFactory()
                        val extractorsFactory = ExtractorsFactory {
                            arrayOf(
                                SubtitleExtractor(
                                    subtitleParserFactory.create(format), format
                                )
                            )
                        }
                        val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(
                            DefaultDataSource.Factory(this), extractorsFactory
                        ).setLoadOnlySelectedTracks(true)
                        try {
                            val method = ProgressiveMediaSource.Factory::class.java.getDeclaredMethod(
                                "enableLazyLoadingWithSingleTrack",
                                Int::class.java,
                                Format::class.java
                            )
                            method.isAccessible = true
                            method.invoke(
                                progressiveMediaSourceFactory, SubtitleExtractor.TRACK_ID,
                                format
                                    .buildUpon()
                                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                    .setCodecs(format.sampleMimeType)
                                    .setCueReplacementBehavior(subtitleParserFactory.getCueReplacementBehavior(format))
                                    .build()
                            )
                        } catch (e: Exception) {
                            Log.w(this::class.simpleName, "failed to set subtitle lazy-loading: ${e.stackTrace}")
                        }
                        progressiveMediaSourceFactory.createMediaSource(MediaItem.fromUri(it.url!!))
                    }.toList()

                    exoPlayer?.setMediaSource(MergingMediaSource(*mediaSources.toTypedArray()))
                    return
                }

                // 2. EN VIVO: Forzamos HLS primero (El más estable para directos)
                streams.isLive && streams.hls != null -> {
                    val hlsMediaSourceFactory = HlsMediaSource.Factory(PlayerHelper.getCacheDataSourceFactory(this@OnlinePlayerService))
                        .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                    val mediaItem = createMediaItem(
                        ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri(),
                        MimeTypes.APPLICATION_M3U8,
                        streams
                    )
                    val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                    exoPlayer?.setMediaSource(mediaSource)
                    return
                }

                // 3. EN VIVO: Fallback a DASH si HLS no existe
                streams.isLive && streams.dash != null -> {
                    val dashUri = ProxyHelper.rewriteUrlUsingProxyPreference(streams.dash).toUri()
                    val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                    exoPlayer?.setMediaItem(mediaItem)
                }

                // 4. VOD: DASH (Generado internamente)
                !streams.isLive && streams.videoStreams.isNotEmpty() -> {
                    val dashUri = PlayerHelper.createDashSource(streams, this@OnlinePlayerService)
                    val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                    exoPlayer?.setMediaItem(mediaItem)
                }

                // 5. VOD: Fallback a HLS
                streams.hls != null -> {
                    val hlsMediaSourceFactory = HlsMediaSource.Factory(PlayerHelper.getCacheDataSourceFactory(this@OnlinePlayerService))
                        .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                    val mediaItem = createMediaItem(
                        ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri(),
                        MimeTypes.APPLICATION_M3U8,
                        streams
                    )
                    val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                    exoPlayer?.setMediaSource(mediaSource)
                    return
                }

                else -> {
                    toastFromMainThread(R.string.unknown_error)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Error crítico parseando el formato del video", e)
            toastFromMainThread(R.string.unknown_error)
        }
    }

    private fun getSubtitleConfigs(): List<SubtitleConfiguration> = streams?.subtitles?.map {
        val roleFlags = getSubtitleRoleFlags(it)
        SubtitleConfiguration.Builder(it.url!!.toUri())
            .setRoleFlags(roleFlags)
            .setLanguage(it.code)
            .setMimeType(it.mimeType).build()
    }.orEmpty()

    private fun createMediaItem(uri: Uri, mimeType: String, streams: Streams) =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(getSubtitleConfigs())
            .setMetadata(streams, videoId)
            .build()
}