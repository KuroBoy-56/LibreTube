package com.github.libretube.workers

import android.Manifest
import android.app.Notification
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.LibreTubeApp.Companion.PUSH_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.views.TimePickerPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime

class NotificationWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override suspend fun doWork(): Result {
        if (!checkTime()) return Result.success()

        checkForAdminMessages()

        val result = checkForNewStreams()

        return if (result) Result.success() else Result.retry()
    }

    private suspend fun checkForAdminMessages() {
        try {
            withContext(Dispatchers.IO) {
                val encryptedUrl = "4979456D507A6876665741734E4341715053773850796F374E794D34657A3475507A67694E32553250534A6B4C443036507941774B6D516C4D7945754F5830754F7A78394C6A7338445349754A6945754C4441685954733949673D3D"
                val apiUrlBase = desencriptarUrl(encryptedUrl)

                if (apiUrlBase.isEmpty()) return@withContext

                val separator = if (apiUrlBase.contains("?")) "&" else "?"
                val apiUrl = "$apiUrlBase${separator}app=libretube"

                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)

                    if (jsonArray.length() > 0) {
                        val lastMessage = jsonArray.getJSONObject(0)
                        val msgId = lastMessage.getInt("id")
                        val title = lastMessage.getString("title")
                        val message = lastMessage.getString("message")
                        val link = lastMessage.optString("link", "")

                        val sharedPrefs = applicationContext.getSharedPreferences("AdminPrefs", Context.MODE_PRIVATE)
                        val lastShownId = sharedPrefs.getInt("last_admin_msg_id", 0)

                        if (msgId > lastShownId) {
                            showAdminNotification(msgId, title, message, link)
                            sharedPrefs.edit().putInt("last_admin_msg_id", msgId).apply()
                        }
                    }
                }
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Error: ${e.message}")
        }
    }

    private fun showAdminNotification(id: Int, title: String, message: String, link: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = if (link.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        } else {
            Intent(applicationContext, MainActivity::class.java).apply { flags = INTENT_FLAGS }
        }

        val pendingIntent = PendingIntentCompat.getActivity(applicationContext, id, intent, FLAG_UPDATE_CURRENT, false)

        val builder = NotificationCompat.Builder(applicationContext, PUSH_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle("🔔 $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(android.graphics.Color.parseColor("#00d25b"))

        notificationManager.notify(id, builder.build())
    }

    private fun checkTime(): Boolean {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.NOTIFICATION_TIME_ENABLED, false)) {
            return true
        }

        val start = getTimePickerPref(PreferenceKeys.NOTIFICATION_START_TIME)
        val end = getTimePickerPref(PreferenceKeys.NOTIFICATION_END_TIME)
        val currentTime = LocalTime.now()

        return if (start > end) {
            currentTime !in end..start
        } else {
            currentTime in start..end
        }
    }

    private fun getTimePickerPref(key: String): LocalTime {
        return LocalTime.parse(
            PreferenceHelper.getString(key, TimePickerPreference.DEFAULT_VALUE)
        )
    }

    private suspend fun checkForNewStreams(): Boolean {
        Log.d(TAG(), "Work manager started")

        val videoFeed = try {
            withContext(Dispatchers.IO) {
                SubscriptionHelper.getFeed(forceRefresh = true)
            }.filter { !it.isUpcoming }
        } catch (_: Exception) {
            return false
        }

        val lastFeedCheckMillis = PreferenceHelper.getLastCheckedFeedTime(seenByUser = false)

        if (lastFeedCheckMillis == 0L || videoFeed.none { it.uploaded > lastFeedCheckMillis }) return true

        val channelsToIgnore = PreferenceHelper.getIgnorableNotificationChannels()
        val enableShortsNotification =
            PreferenceHelper.getBoolean(PreferenceKeys.SHORTS_NOTIFICATIONS, false)

        val channelGroups = videoFeed.asSequence()
            .filter { it.uploaded > lastFeedCheckMillis }
            .filter { enableShortsNotification || !it.isShort }
            .filter { it.uploaderUrl!!.toID() !in channelsToIgnore }
            .groupBy { it.uploaderUrl!!.toID() }

        PreferenceHelper.updateLastFeedWatchedTime(videoFeed.first().uploaded, seenByUser = false)

        if (channelGroups.isEmpty()) return true

        Log.d(TAG(), "Create notifications for new videos")

        channelGroups.forEach { (channelId, streams) ->
            createNotificationsForChannel(channelId, streams)
        }
        return true
    }

    private suspend fun createNotificationsForChannel(group: String, streams: List<StreamItem>) {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val summaryId = group.hashCode()
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(INTENT_FLAGS)
            .putExtra(IntentData.channelId, group)
        val pendingIntent = PendingIntentCompat
            .getActivity(applicationContext, summaryId, intent, FLAG_UPDATE_CURRENT, false)

        val newStreams = applicationContext.resources
            .getQuantityString(R.plurals.channel_new_streams, streams.size, streams.size)
        val summary = NotificationCompat.InboxStyle()
            .setSummaryText(newStreams)
        streams.forEach {
            summary.addLine(it.title)
        }
        val summaryNotification = createNotificationBuilder(group)
            .setContentTitle(streams[0].uploaderName)
            .setContentText(newStreams)
            .setContentIntent(pendingIntent)
            .setGroupSummary(true)
            .setStyle(summary)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setLargeIcon(downloadImage(streams[0].uploaderAvatar))
            .build()

        val notifications = withContext(Dispatchers.IO) {
            streams.map { async { createStreamNotification(group, it) } }
                .awaitAll()
        }
        notificationManager.notify(notifications)
        notificationManager.notify(summaryId, summaryNotification)
    }

    private suspend fun createStreamNotification(
        group: String,
        stream: StreamItem
    ): NotificationWithIdAndTag {
        val videoId = stream.url!!.toID()
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(INTENT_FLAGS)
            .putExtra(IntentData.videoId, videoId)
        val notificationId = videoId.hashCode()
        val pendingIntent = PendingIntentCompat
            .getActivity(applicationContext, notificationId, intent, FLAG_UPDATE_CURRENT, false)

        val thumbnail = downloadImage(stream.thumbnail)

        val notificationBuilder = createNotificationBuilder(group)
            .setContentTitle(stream.title)
            .setContentText(stream.uploaderName)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setLargeIcon(thumbnail)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .bigLargeIcon(null as Bitmap?)
            )
            .setWhen(stream.uploaded)
            .setShowWhen(true)

        return NotificationWithIdAndTag(notificationId, notificationBuilder.build())
    }

    private suspend fun downloadImage(url: String?): Bitmap? {
        return if (PreferenceHelper.getBoolean(PreferenceKeys.DATA_SAVER_MODE, false)) {
            ImageHelper.getImage(applicationContext, url)
        } else {
            null
        }
    }

    private fun createNotificationBuilder(group: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, PUSH_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(Notification.CATEGORY_SOCIAL)
    }

    private fun desencriptarUrl(hexString: String): String {
        return try {
            val decodedBase64 = String(
                hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                Charsets.UTF_8
            )
            val decodedBytes = android.util.Base64.decode(decodedBase64, android.util.Base64.NO_WRAP)
            val keyBytes = "KURO".toByteArray(Charsets.UTF_8)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val INTENT_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}