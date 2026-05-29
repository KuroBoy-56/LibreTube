package com.github.libretube.helpers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.TrendingCategory
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.round
import com.github.libretube.helpers.LocaleHelper.getDetectedCountry
import kotlin.math.roundToInt

object PreferenceHelper {
    private val TAG = PreferenceHelper::class.simpleName

    private class PreferenceMigration(
        val fromVersion: Int, val toVersion: Int, val onMigration: () -> Unit
    )

    lateinit var settings: SharedPreferences
    private lateinit var authSettings: SharedPreferences

    private const val USER_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    private val MIGRATIONS = arrayOf(
        PreferenceMigration(0, 1) {
            LibreTubeApp.instance.resources
                .getStringArray(R.array.sponsorBlockSegments)
                .forEach { category ->
                    val key = "${category}_category"
                    val stored = getString(key, "visible")
                    if (stored == "visible") {
                        putString(key, SbSkipOptions.MANUAL.name.lowercase())
                    }
                }
        },
        PreferenceMigration(1, 2) {
            putString(PreferenceKeys.TRENDING_CATEGORY, TrendingCategory.LIVE.name)
        },
        PreferenceMigration(2, 3) {
            listOf(
                "player_audio_format", "lbry_hls", "confirm_unsubscribing", "legacy_subscriptions",
                "legacy_subscriptions_columns", "filter_history_type", "use_hls", "last_stream_video_id",
                "last_watched_feed_time", "last_feed_refresh_timestamp_millis", "custom_playback_speed",
                "background_playback_speed", "player_resize_mode", "alternative_videos_layout",
                "clearCustomInstances", "auth", "image_proxy_url", "dearrow", "unlimited_search_history",
                "sb_highlights", "audio_only_mode", "last_stream_video_id", "last_watched_feed_time",
                "sb_contribute_key", "dearrow_contribute_key", "sb_user_id", "fallback_piped_proxy",
                "picture_in_picture", "pause_on_quit", "save_feed", "filer_feed", "max_concurrent_downloads",
                "grid", "sleep_timer_toggle", "sleep_timer_delay", "alternative_player_layout",
                "auto_rotation", "sb_show_markers", "autoplay", "sb_skip_manually_key",
                "player_screen_brightness", "selected_filer_feed", "selected_feed_filer",
                "feed_sort_oder", "player_video_format", "watch_position_toggle", "background_playback_speed",
                "break_reminder_toggle", "break_reminder", "notification_open_queue", "data_saver_mode",
                "import_from_yt", "export_subs", "last_stream_video_id", "show_open_with",
                "player_swipe_control", "progressive_loading_interval", "limit_hls", "nav_bar_items",
                "trending_layout", "default_tab", "backup_settings", "restore_settings",
                "hide_trending_page", "sb_skip_manually", "download_location", "download_folder",
            ).map { key -> remove(key) }
        },
        PreferenceMigration(3, 4) {
            listOf("video_codecs", "audio_codecs").map { remove(it) }
        },
        PreferenceMigration(4, 5) {
            remove("remember_playback_speed")
        },
        PreferenceMigration(5, 6) {
            val currentSpeed = (settings.getString(PreferenceKeys.PLAYBACK_SPEED, null)
                ?: return@PreferenceMigration).replace("F", "").toFloat()
            val speed = (currentSpeed * 4f).roundToInt() / 4f
            putString(PreferenceKeys.PLAYBACK_SPEED, speed.toString())
        },
        PreferenceMigration(6, 7) {
            remove("disable_video_image_proxy")
        },
    )

    fun initialize(context: Context) {
        settings = getDefaultSharedPreferences(context)
        authSettings = getAuthenticationPreferences(context)

        // Servidor fijo y estable (Kavin - El estándar de la comunidad)
        if (getString("api_url", "").isEmpty() || getString("api_url", "").contains("pjsf")) {
            settings.edit(commit = true) {
                putString("api_url", "https://pipedapi.kavin.rocks")
                putString("selectInstance", "https://pipedapi.kavin.rocks")
                putString("frontend_url", "https://piped.video")
            }
        }
        
        // Configurar 720p como resolución por defecto para nuevos usuarios
        if (getString(PreferenceKeys.DEFAULT_RESOLUTION, "").isEmpty()) {
            settings.edit(commit = true) {
                putString(PreferenceKeys.DEFAULT_RESOLUTION, "720p")
                putString(PreferenceKeys.DEFAULT_RESOLUTION_MOBILE, "720p")
            }
        }
    }

    fun migrate() {
        var currentPrefVersion = getInt(PreferenceKeys.PREFERENCE_VERSION, 0)

        while (currentPrefVersion < MIGRATIONS.count()) {
            val next = currentPrefVersion + 1

            val migration =
                MIGRATIONS.find { it.fromVersion == currentPrefVersion && it.toVersion == next }
            Log.i(TAG, "Performing migration from $currentPrefVersion to $next")
            migration?.onMigration?.invoke()

            currentPrefVersion++
            putInt(PreferenceKeys.PREFERENCE_VERSION, currentPrefVersion)
        }
    }

    fun putString(key: String, value: String) { settings.edit(commit = true) { putString(key, value) } }
    fun putBoolean(key: String, value: Boolean) { settings.edit(commit = true) { putBoolean(key, value) } }
    fun putInt(key: String, value: Int) { settings.edit(commit = true) { putInt(key, value) } }
    fun putLong(key: String, value: Long) { settings.edit(commit = true) { putLong(key, value) } }
    fun putStringSet(key: String, value: Set<String>) { settings.edit(commit = true) { putStringSet(key, value) } }
    fun remove(key: String) { settings.edit(commit = true) { remove(key) } }

    fun getString(key: String?, defValue: String): String = settings.getString(key, defValue) ?: defValue
    fun getBoolean(key: String?, defValue: Boolean): Boolean = settings.getBoolean(key, defValue)

    fun getInt(key: String?, defValue: Int): Int {
        return runCatching {
            settings.getInt(key, defValue)
        }.getOrElse { settings.getLong(key, defValue.toLong()).toInt() }
    }

    fun getLong(key: String?, defValue: Long): Long = settings.getLong(key, defValue)
    fun getStringSet(key: String?, defValue: Set<String>): Set<String> = settings.getStringSet(key, defValue).orEmpty()
    fun clearPreferences() { settings.edit { clear() } }

    fun getToken(): String = authSettings.getString(PreferenceKeys.TOKEN, "")!!
    fun setToken(newValue: String) { authSettings.edit { putString(PreferenceKeys.TOKEN, newValue) } }

    fun getUsername(): String = authSettings.getString(PreferenceKeys.USERNAME, "")!!
    fun setUsername(newValue: String) { authSettings.edit { putString(PreferenceKeys.USERNAME, newValue) } }

    fun updateLastFeedWatchedTime(time: Long, seenByUser: Boolean) {
        if (getLastCheckedFeedTime(false) < time)
            putLong(PreferenceKeys.LAST_REFRESHED_FEED_TIME, time)

        if (seenByUser && getLastCheckedFeedTime(true) < time)
            putLong(PreferenceKeys.LAST_USER_SEEN_FEED_TIME, time)
    }

    fun getLastCheckedFeedTime(seenByUser: Boolean): Long {
        val key = if (seenByUser) PreferenceKeys.LAST_USER_SEEN_FEED_TIME else PreferenceKeys.LAST_REFRESHED_FEED_TIME
        return getLong(key, 0)
    }

    fun saveErrorLog(log: String) { putString(PreferenceKeys.ERROR_LOG, log) }
    fun getErrorLog(): String = getString(PreferenceKeys.ERROR_LOG, "")

    fun getIgnorableNotificationChannels(): List<String> = getString(PreferenceKeys.IGNORED_NOTIFICATION_CHANNELS, "").split(",")

    fun isChannelNotificationIgnorable(channelId: String): Boolean = getIgnorableNotificationChannels().any { it == channelId }

    fun toggleIgnorableNotificationChannel(channelId: String) {
        val ignorableChannels = getIgnorableNotificationChannels().toMutableList()
        if (ignorableChannels.contains(channelId)) ignorableChannels.remove(channelId)
        else ignorableChannels.add(channelId)

        settings.edit {
            putString(PreferenceKeys.IGNORED_NOTIFICATION_CHANNELS, ignorableChannels.joinToString(","))
        }
    }

    fun getSponsorBlockUserID(): String {
        var uuid = getString(PreferenceKeys.SB_USER_ID, "")
        if (uuid.isEmpty()) {
            uuid = (0 until 30).map { USER_ID_CHARS.random() }.joinToString("")
            putString(PreferenceKeys.SB_USER_ID, uuid)
        }
        return uuid
    }

    fun rotateInstance(): String {
        // SERVIDORES TOP 2024 (Máximo Uptime y Calidad)
        val backupServers = listOf(
            "https://pipedapi.adminforge.de",
            "https://pi.pjsf.fr",
            "https://api-piped.mha.fi",
            "https://pipedapi.official.yt",
            "https://pipedapi.astoria.rocks",
            "https://pipedapi.kavin.rocks"
        )
        val current = getString(PreferenceKeys.FETCH_INSTANCE, "https://pipedapi.adminforge.de")
        val currentIndex = backupServers.indexOf(current)
        val nextIndex = (currentIndex + 1) % backupServers.size
        val nextInstance = backupServers[nextIndex]
        
        putString(PreferenceKeys.FETCH_INSTANCE, nextInstance)
        putString("api_url", nextInstance)

        return nextInstance
    }

    fun getTrendingRegion(context: Context): String {
        val regionPref = PreferenceHelper.getString(PreferenceKeys.REGION, "sys")
        return if (regionPref == "sys") {
            getDetectedCountry(context).uppercase()
        } else {
            regionPref
        }
    }

    private fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    private fun getAuthenticationPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PreferenceKeys.AUTH_PREF_FILE, Context.MODE_PRIVATE)
    }
}