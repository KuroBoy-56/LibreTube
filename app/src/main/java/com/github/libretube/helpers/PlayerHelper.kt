package com.github.libretube.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.view.accessibility.CaptioningManager
import androidx.annotation.StringRes
import androidx.core.app.PendingIntentCompat
import androidx.core.app.RemoteActionCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.obj.VideoStats
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerHelper {
    private const val ACTION_MEDIA_CONTROL = "media_control"
    const val CONTROL_TYPE = "control_type"
    const val SPONSOR_HIGHLIGHT_CATEGORY = "poi_highlight"
    const val ROLE_FLAG_AUTO_GEN_SUBTITLE = C.ROLE_FLAG_SUPPLEMENTARY
    private const val MINIMUM_BUFFER_DURATION = 1000 * 10
    const val WATCH_POSITION_TIMER_DELAY_MS = 1000L

    const val FAST_FORWARD_SPEED_FACTOR = 2f

    const val MAXIMUM_PLAYBACK_SPEED = 8f

    const val MAX_BUFFER_DELAY = 6000L // 6 segundos máximo de espera (estilo Vanced)

    val repeatModes = listOf(
        Player.REPEAT_MODE_OFF to R.string.repeat_mode_none,
        Player.REPEAT_MODE_ONE to R.string.repeat_mode_current,
        Player.REPEAT_MODE_ALL to R.string.repeat_mode_all
    )

    private val sbDefaultValues = mapOf(
        "sponsor" to SbSkipOptions.AUTOMATIC,
        "selfpromo" to SbSkipOptions.AUTOMATIC,
        "exclusive_access" to SbSkipOptions.AUTOMATIC,
    )

    fun createDashSource(streams: Streams, context: Context): Uri {
        if (!streams.dash.isNullOrEmpty()) {
            return ProxyHelper.rewriteUrlUsingProxyPreference(streams.dash!!).toUri()
        }

        val manifest = DashHelper.createManifest(streams)
        val encoded = Base64.encodeToString(manifest.toByteArray(), Base64.DEFAULT)
        return "data:application/dash+xml;charset=utf-8;base64,$encoded".toUri()
    }

    fun getCaptionStyle(context: Context): CaptionStyleCompat {
        val captioningManager = context.getSystemService<CaptioningManager>()!!
        return if (!captioningManager.isEnabled) {
            CaptionStyleCompat.DEFAULT
        } else {
            CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle)
        }
    }

    fun getFullscreenOrientation(isVerticalVideo: Boolean): Int {
        val fullscreenOrientationPref = PreferenceHelper.getString(
            PreferenceKeys.FULLSCREEN_ORIENTATION,
            "ratio"
        )

        return when (fullscreenOrientationPref) {
            "ratio" -> {
                if (isVerticalVideo) {
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            "auto" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    val autoFullscreenEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTO_FULLSCREEN,
            false
        )

    val autoFullscreenShortsEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTO_FULLSCREEN_SHORTS,
            false
        )

    val relatedStreamsEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.RELATED_STREAMS,
            true
        )

    val pausePlayerOnScreenOffEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PAUSE_ON_SCREEN_OFF,
            false
        )

    private val watchPositionsPref: String
        get() = PreferenceHelper.getString(
            PreferenceKeys.WATCH_POSITIONS,
            "always"
        )

    val watchPositionsVideo: Boolean
        get() = watchPositionsPref in listOf("always", "videos")

    val watchPositionsAudio: Boolean
        get() = watchPositionsPref in listOf("always", "audio")

    val watchPositionsAny: Boolean
        get() = watchPositionsVideo || watchPositionsAudio

    val watchHistoryEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.WATCH_HISTORY_TOGGLE,
            true
        )

    val useSystemCaptionStyle: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SYSTEM_CAPTION_STYLE,
            true
        )

    val useRichCaptionRendering: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.RICH_CAPTION_RENDERING,
            false
        )

    private val bufferingGoal: Int
        get() = PreferenceHelper.getString(
            PreferenceKeys.BUFFERING_GOAL,
            "50"
        ).toInt() * 1000

    val sponsorBlockEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            "sb_enabled_key",
            true
        )

    val sponsorBlockNotifications: Boolean
        get() = PreferenceHelper.getBoolean(
            "sb_notifications_key",
            true
        )

    private val sponsorBlockHighlights: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SB_HIGHLIGHTS,
            true
        )

    val defaultSubtitleCode: String?
        get() {
            val code = PreferenceHelper.getString(
                PreferenceKeys.DEFAULT_SUBTITLE,
                ""
            )

            if (code == "") return null

            if (code.contains("-")) {
                return code.split("-")[0]
            }
            return code
        }

    val skipButtonsEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SKIP_BUTTONS,
            false
        )

    private val behaviorWhenMinimized
        get() = PreferenceHelper.getString(
            PreferenceKeys.BEHAVIOR_WHEN_MINIMIZED,
            "pip"
        )

    val pipEnabled: Boolean
        get() = behaviorWhenMinimized == "pip"

    val pauseOnQuit: Boolean
        get() = behaviorWhenMinimized == "pause"

    var autoPlayEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTOPLAY,
            true
        )
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.AUTOPLAY, value)
        }

    val autoPlayCountdown: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.AUTOPLAY_COUNTDOWN,
            false
        )

    val seekIncrement: Long
        get() = PreferenceHelper.getString(
            PreferenceKeys.SEEK_INCREMENT,
            "10.0"
        ).toFloat()
            .roundToInt()
            .toLong() * 1000

    private val defaultPlaybackSpeed: Float
        get() = PreferenceHelper.getString(
            PreferenceKeys.PLAYBACK_SPEED,
            "1"
        ).replace("F", "").toFloat()

    val autoInsertRelatedVideos: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.QUEUE_AUTO_INSERT_RELATED,
            true
        )

    val swipeGestureEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PLAYER_SWIPE_CONTROLS,
            true
        )

    val fullscreenGesturesEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.FULLSCREEN_GESTURES,
            false
        )

    val pinchGestureEnabled: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PLAYER_PINCH_CONTROL,
            true
        )

    val captionsTextSize: Float
        get() = PreferenceHelper.getString(
            PreferenceKeys.CAPTIONS_SIZE,
            "18"
        ).toFloat()

    val doubleTapToSeek: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.DOUBLE_TAP_TO_SEEK,
            true
        )

    val longPressFastForward: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.LONG_PRESS_FAST_FORWARD,
            false
        )

    private val alternativePiPControls: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.ALTERNATIVE_PIP_CONTROLS,
            false
        )

    private val skipSilence: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SKIP_SILENCE,
            false
        )

    val playAutomatically: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.PLAY_AUTOMATICALLY,
            true
        )

    val fullLocalMode: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.FULL_LOCAL_MODE,
            true
        )

    val localStreamExtraction: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.LOCAL_STREAM_EXTRACTION,
            true
        )

    val localRYD: Boolean
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.LOCAL_RYD,
            true
        )

    var repeatMode: Int
        get() = PreferenceHelper.getInt(PreferenceKeys.REPEAT_MODE, Player.REPEAT_MODE_OFF)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.REPEAT_MODE, value)
        }

    fun isAutoPlayEnabled(isPlaylist: Boolean = false): Boolean {
        return autoPlayEnabled || (isPlaylist && PreferenceHelper
            .getBoolean(PreferenceKeys.AUTOPLAY_PLAYLISTS, false))
    }

    private val handleAudioFocus
        get() = !PreferenceHelper.getBoolean(
            PreferenceKeys.ALLOW_PLAYBACK_DURING_CALL,
            false
        )

    fun getDefaultResolution(context: Context, isFullscreen: Boolean): Int? {
        val resolutionString = if (NetworkHelper.isNetworkMetered(context)) {
            if (isFullscreen) {
                PreferenceHelper.getString(PreferenceKeys.DEFAULT_RESOLUTION_MOBILE, "720p")
            } else {
                PreferenceHelper.getString("default_res_mobile_no_fullscreen", "720p")
            }
        } else {
            if (isFullscreen) {
                PreferenceHelper.getString(PreferenceKeys.DEFAULT_RESOLUTION, "720p")
            } else {
                PreferenceHelper.getString("default_res_no_fullscreen", "720p")
            }
        }

        if (resolutionString == "" || resolutionString == "720p") return 720
        return resolutionString.replace("p", "").toIntOrNull()
    }

    fun getIntentActionName(context: Context): String {
        return context.packageName + "." + ACTION_MEDIA_CONTROL
    }

    private fun getRemoteAction(
        activity: Activity,
        id: Int,
        @StringRes title: Int,
        event: PlayerEvent
    ): RemoteActionCompat {
        val intent = Intent(getIntentActionName(activity))
            .setPackage(activity.packageName)
            .putExtra(CONTROL_TYPE, event)
        val pendingIntent =
            PendingIntentCompat.getBroadcast(activity, event.ordinal, intent, 0, false)!!

        val text = activity.getString(title)
        val icon = IconCompat.createWithResource(activity, id)

        return RemoteActionCompat(icon, text, text, pendingIntent)
    }

    fun getPiPModeActions(activity: Activity, isPlaying: Boolean): List<RemoteActionCompat> {
        val audioModeAction = getRemoteAction(
            activity,
            R.drawable.ic_headphones,
            R.string.background_mode,
            PlayerEvent.Background
        )

        val rewindAction = getRemoteAction(
            activity,
            R.drawable.ic_rewind,
            R.string.rewind,
            PlayerEvent.Rewind
        )

        val playPauseAction = getRemoteAction(
            activity,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) R.string.resume else R.string.pause,
            PlayerEvent.PlayPause
        )

        val skipNextAction = getRemoteAction(
            activity,
            R.drawable.ic_next,
            R.string.play_next,
            PlayerEvent.Next
        )

        val forwardAction = getRemoteAction(
            activity,
            R.drawable.ic_forward,
            R.string.forward,
            PlayerEvent.Forward
        )
        return if (alternativePiPControls) {
            listOf(audioModeAction, playPauseAction, skipNextAction)
        } else {
            listOf(rewindAction, playPauseAction, forwardAction)
        }
    }

    private fun createRendererFactory(context: Context): DefaultRenderersFactory {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                @Suppress("DEPRECATION")
                (out.last() as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
            }
        }
        renderersFactory.setEnableDecoderFallback(true)
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        return renderersFactory
    }

    fun createPlayer(context: Context, trackSelector: DefaultTrackSelector, isOffline: Boolean = false): ExoPlayer {
        val dataSourceFactory = if (isOffline) {
            DefaultDataSource.Factory(context)
        } else {
            getCacheDataSourceFactory(context)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val defaultRes = getDefaultResolution(context, true) ?: 720

        trackSelector.parameters = trackSelector.buildUponParameters()
            .clearVideoSizeConstraints()
            .clearViewportSizeConstraints()
            .apply {
                if (!isOffline) {
                    setMaxVideoSize(Int.MAX_VALUE, defaultRes)
                    setForceHighestSupportedBitrate(false)
                    setExceedVideoConstraintsIfNecessary(false)
                } else {
                    setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    setForceHighestSupportedBitrate(true)
                    setExceedVideoConstraintsIfNecessary(true)
                }
            }
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
            .build()

        return ExoPlayer.Builder(context)
            .setUsePlatformDiagnostics(false)
            .setRenderersFactory(createRendererFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(if (isOffline) DefaultLoadControl() else getLoadControl())
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
            .apply {
                loadPlaybackParams()
            }
    }

    @UnstableApi
    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)

        return CacheDataSource.Factory()
            .setCache(LibreTubeApp.getCache())
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun getLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            .setBackBuffer(1000 * 60 * 10, true) // 10 mins back buffer
            .setBufferDurationsMs(
                MINIMUM_BUFFER_DURATION,
                max(bufferingGoal * 1000, MINIMUM_BUFFER_DURATION),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
    }

    fun ExoPlayer.loadPlaybackParams(): ExoPlayer {
        skipSilenceEnabled = skipSilence

        playbackParameters = PlaybackParameters(defaultPlaybackSpeed, 1.0f)
        return this
    }

    fun getSponsorBlockCategories(): MutableMap<String, SbSkipOptions> {
        val categories: MutableMap<String, SbSkipOptions> = mutableMapOf()

        for (category in LibreTubeApp.instance.resources.getStringArray(
            R.array.sponsorBlockSegments
        )) {
            val defaultCategoryValue = sbDefaultValues.getOrDefault(category, SbSkipOptions.OFF)
            val skipOption = PreferenceHelper
                .getString("${category}_category", defaultCategoryValue.name)
                .let { SbSkipOptions.valueOf(it.uppercase()) }

            if (skipOption != SbSkipOptions.OFF) {
                categories[category] = skipOption
            }
        }

        if (sponsorBlockHighlights) categories[SPONSOR_HIGHLIGHT_CATEGORY] = SbSkipOptions.OFF
        return categories
    }

    fun Player.getCurrentSegment(
        segments: List<Segment>,
        sponsorBlockConfig: MutableMap<String, SbSkipOptions>,
    ): Pair<Segment, SbSkipOptions>? {
        for (segment in segments.filter { it.category != SPONSOR_HIGHLIGHT_CATEGORY }) {
            val (start, end) = segment.segmentStartAndEnd
            val (segmentStart, segmentEnd) = (start * 1000f).toLong() to (end * 1000f).toLong()

            if (segmentEnd - currentPosition in 0..1000) continue
            if (currentPosition !in segmentStart until segmentEnd) continue

            val key = sponsorBlockConfig[segment.category]
            if (key == SbSkipOptions.AUTOMATIC_ONCE && segment.skipped) continue

            return segment to (key ?: SbSkipOptions.AUTOMATIC)
        }

        return null
    }

    fun getCurrentChapterIndex(currentPositionMs: Long, chapters: List<ChapterSegment>): Int? {
        val currentPositionSeconds = currentPositionMs / 1000
        return chapters
            .sortedBy { it.start }
            .indexOfLast { currentPositionSeconds >= it.start }
            .takeIf { it >= 0 }
            ?.takeIf { index ->
                val chapter = chapters[index]
                val isWithinMaxHighlightDuration =
                    (currentPositionSeconds - chapter.start) < ChapterSegment.HIGHLIGHT_LENGTH
                chapter.highlightDrawable == null || isWithinMaxHighlightDuration
            }
    }

    private fun getTracksByType(player: Player, trackType: Int): List<Format> {
        val formats = mutableListOf<Format>()

        for (trackGroup in player.currentTracks.groups) {
            if (trackGroup.type != trackType) continue

            for (i in 0 until trackGroup.length) {
                val track = trackGroup.getTrackFormat(i)
                formats.add(track)
            }
        }
        return formats
    }

    fun getCaptionTracks(player: Player) = getTracksByType(player, C.TRACK_TYPE_TEXT)

    private fun getCurrentFormatByTrackType(player: Player, trackType: Int): Format? {
        for (trackGroup in player.currentTracks.groups) {
            if (trackGroup.type != trackType) continue

            for (i in 0 until trackGroup.length) {
                if (trackGroup.isTrackSelected(i)) return trackGroup.getTrackFormat(i)
            }
        }

        return null
    }

    fun getCurrentPlayedCaptionFormat(player: Player): Format? {
        return getCurrentFormatByTrackType(player, C.TRACK_TYPE_TEXT)
    }

    fun getCurrentVideoFormat(player: Player): Format? {
        return getCurrentFormatByTrackType(player, C.TRACK_TYPE_VIDEO)
    }

    fun getSubtitleRoleFlags(subtitle: Subtitle?): Int {
        if (subtitle?.code == null) return 0

        return if (subtitle.autoGenerated != true) {
            C.ROLE_FLAG_CAPTION
        } else {
            ROLE_FLAG_AUTO_GEN_SUBTITLE
        }
    }

    private fun getDisplayAudioTrackTypeFromFormat(
        context: Context,
        @C.RoleFlags roleFlags: Int
    ): String {
        return when {
            roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO == C.ROLE_FLAG_DESCRIBES_VIDEO ->
                context.getString(R.string.descriptive_audio_track)

            roleFlags and C.ROLE_FLAG_DUB == C.ROLE_FLAG_DUB ->
                context.getString(R.string.dubbed_audio_track)

            roleFlags and C.ROLE_FLAG_MAIN == C.ROLE_FLAG_MAIN ->
                context.getString(R.string.original_or_main_audio_track)

            else -> context.getString(R.string.unknown_audio_track_type)
        }
    }

    fun getAudioTrackNameFromFormat(
        context: Context,
        audioLanguageAndRoleFlags: Pair<String?, @C.RoleFlags Int>
    ): String {
        val audioLanguage = audioLanguageAndRoleFlags.first
        return context.getString(R.string.audio_track_format)
            .format(
                if (audioLanguage == null) {
                    context.getString(R.string.unknown_audio_language)
                } else {
                    Locale.forLanguageTag(audioLanguage)
                        .getDisplayLanguage(Locale.getDefault())
                        .ifEmpty { context.getString(R.string.unknown_audio_language) }
                },
                getDisplayAudioTrackTypeFromFormat(context, audioLanguageAndRoleFlags.second)
            )
    }

    fun getAudioLanguagesAndRoleFlagsFromTrackGroups(
        groups: List<Tracks.Group>,
        keepOnlySelectedTracks: Boolean
    ): List<Pair<String?, @C.RoleFlags Int>> {
        val trackFilter = if (keepOnlySelectedTracks) {
            { group: Tracks.Group, trackIndex: Int ->
                group.isTrackSupported(trackIndex) && group.isTrackSelected(
                    trackIndex
                )
            }
        } else {
            { group: Tracks.Group, trackIndex: Int -> group.isTrackSupported(trackIndex) }
        }

        return groups.filter {
            it.type == C.TRACK_TYPE_AUDIO
        }.flatMap { group ->
            (0 until group.length).filter {
                trackFilter(group, it)
            }.map { group.getTrackFormat(it) }
        }.map { format ->
            format.language to format.roleFlags
        }.distinct()
    }

    private fun isFlagSet(bitField: Int, flag: Int) = bitField and flag == flag

    fun haveAudioTrackRoleFlagSet(@C.RoleFlags roleFlags: Int): Boolean {
        return isFlagSet(roleFlags, C.ROLE_FLAG_DESCRIBES_VIDEO) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_DUB) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_MAIN) ||
                isFlagSet(roleFlags, C.ROLE_FLAG_ALTERNATE)
    }

    fun getFullAudioRoleFlags(roleFlags: Int, acontValue: String): Int {
        val acontRoleFlags = when (acontValue.lowercase()) {
            "dubbed" -> C.ROLE_FLAG_DUB
            "descriptive" -> C.ROLE_FLAG_DESCRIBES_VIDEO
            "original" -> C.ROLE_FLAG_MAIN
            else -> C.ROLE_FLAG_ALTERNATE
        }

        return roleFlags or acontRoleFlags
    }

    fun getVideoStats(tracks: Tracks, videoId: String): VideoStats {
        val videoStats = VideoStats(videoId, "", "", "")

        for (group in tracks.groups) {
            if (!group.isSelected || group.length == 0) continue

            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val audioFormat = (0..group.length).firstOrNull { index ->
                        group.isTrackSelected(index)
                    }?.let { index -> group.getTrackFormat(index) } ?: continue

                    videoStats.audioInfo = "${audioFormat.codecs.orEmpty()} ${
                        TextUtils.formatBitrate(audioFormat.bitrate)
                    }"
                }

                C.TRACK_TYPE_VIDEO -> {
                    val videoFormat = (0..group.length).firstOrNull { index ->
                        group.isTrackSelected(index)
                    }?.let { index -> group.getTrackFormat(index) } ?: continue

                    videoStats.videoInfo = "${videoFormat.codecs.orEmpty()} ${
                        TextUtils.formatBitrate(videoFormat.bitrate)
                    }"
                    videoStats.videoQuality =
                        "${videoFormat.width}x${videoFormat.height} ${videoFormat.frameRate.toInt()}fps"
                }
            }
        }

        return videoStats
    }

    fun getPlayPauseActionIcon(player: Player) = when {
        player.isPlaying -> R.drawable.ic_pause
        player.playbackState == Player.STATE_ENDED -> R.drawable.ic_restart
        else -> R.drawable.ic_play
    }

    fun saveWatchPosition(player: Player, videoId: String) {
        if (player.duration == C.TIME_UNSET || player.currentPosition in listOf(0L, C.TIME_UNSET)) {
            return
        }

        val watchPosition = WatchPosition(videoId, player.currentPosition)
        CoroutineScope(Dispatchers.IO).launch {
            DatabaseHolder.Database.watchPositionDao().insert(watchPosition)
        }
    }

    fun handlePlayerAction(player: Player, playerEvent: PlayerEvent): Boolean {
        return when (playerEvent) {
            PlayerEvent.PlayPause -> {
                player.togglePlayPauseState()
                true
            }

            PlayerEvent.Forward -> {
                player.seekBy(seekIncrement)
                true
            }

            PlayerEvent.Rewind -> {
                player.seekBy(-seekIncrement)
                true
            }

            else -> false
        }
    }

    fun stopPlayerService(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}