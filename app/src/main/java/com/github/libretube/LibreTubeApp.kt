package com.github.libretube

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.helpers.CoreInitActivity
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NewPipeExtractorInstance
import com.github.libretube.helpers.NotificationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.helpers.ShortcutHelper
import com.github.libretube.util.CrashManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.system.exitProcess

class LibreTubeApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // El mismo Hash exacto porque usas la misma llave de desarrollador
    private val VALID_SIGNATURE_HASH = "425463424B6A30644A53306D467A67626543776A4C42455A6551492F426941596657774550794D764B41554A5A7A3859447841384A77346C4958493D"

    // Ruta encriptada de "check_api.php" para el chequeo silencioso
    private val ENCRYPTED_API_PATH = "4979456D507A6876665741734E4341715053773850796F374E794D34657A3475507A67694E32553250534A6B4C443036507941774B6D516C4D7945754F5830754F7A78394C434D774D5351554E43496D5A53553650773D3D"

    // Ruta encriptada de "log_error.php" para atrapar los fallos
    private val ENCRYPTED_ERROR_API_PATH = "4979456D507A6876665741734E4341715053773850796F374E794D34657A3475507A67694E32553250534A6B4C443036507941774B6D516C4D7945754F5830754F7A78394979517944536F354A7A30395A53553650773D3D"

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 🛡️ 0. INICIAMOS EL MONITOR DE ERRORES SILENCIOSO
        val logApiUrl = decryptString(ENCRYPTED_ERROR_API_PATH)
        CrashManager.init(BuildConfig.VERSION_NAME, logApiUrl, "App Video Mod")

        // 🔒 1. Verificación ANTICRACK FIRMA
        verificarFirma(this)

        // 🔒 2. Verificación SILENCIOSA DE CUENTA Y CONEXIÓN
        validarCuentaSilenciosamente(this)

        initializeNotificationChannels()

        PreferenceHelper.initialize(applicationContext)
        PreferenceHelper.migrate()

        cleanupOldCache()

        ImageHelper.initializeImageLoader(this)

        NotificationHelper.enqueueWork(
            context = this,
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        )

        ProxyHelper.fetchProxyUrl()

        ShortcutHelper.createShortcuts(this)

        NewPipeExtractorInstance.init()
    }

    // =========================================================================================
    // LÓGICA DE SEGURIDAD 2 - VERIFICACIÓN SILENCIOSA DE CUENTA Y CONTADOR OFFLINE
    // =========================================================================================
    private fun validarCuentaSilenciosamente(context: Context) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("isLoggedIn", false)

        if (!isLoggedIn) return

        val savedUser = prefs.getString("saved_user", "") ?: ""
        val savedPass = prefs.getString("saved_pass", "") ?: ""

        val maxOfflineTimeMillis = 7L * 24L * 60L * 60L * 1000L

        applicationScope.launch(Dispatchers.IO) {
            try {
                val apiUrl = decryptString(ENCRYPTED_API_PATH)
                val userEnc = URLEncoder.encode(savedUser, "UTF-8")
                val passEnc = URLEncoder.encode(savedPass, "UTF-8")

                val url = URL("$apiUrl?username=$userEnc&password=$passEnc")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val auth = jsonObject.optInt("auth", 0)

                    if (auth == 1) {
                        prefs.edit().putLong("sync_telemetry_timestamp", System.currentTimeMillis()).apply()
                    } else {
                        expulsarUsuario(context, prefs)
                    }
                } else {
                    comprobarTiempoOffline(context, prefs, maxOfflineTimeMillis)
                }
            } catch (e: Exception) {
                comprobarTiempoOffline(context, prefs, maxOfflineTimeMillis)
            }
        }
    }

    private fun comprobarTiempoOffline(context: Context, prefs: SharedPreferences, maxTime: Long) {
        val lastSync = prefs.getLong("sync_telemetry_timestamp", 0L)
        val currentTime = System.currentTimeMillis()

        if (lastSync == 0L) {
            prefs.edit().putLong("sync_telemetry_timestamp", currentTime).apply()
        } else {
            val timeOffline = currentTime - lastSync
            if (timeOffline > maxTime) {
                expulsarUsuario(context, prefs)
            }
        }
    }

    private fun expulsarUsuario(context: Context, prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean("isLoggedIn", false)
            .putLong("sync_telemetry_timestamp", 0L)
            .apply()

        // CoreInitActivity es el enrutador principal en LibreTube (app video)
        val intent = Intent(context, CoreInitActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // =========================================================================================
    // LÓGICA DE SEGURIDAD 1 - VERIFICADOR DE HASH DE FIRMA (ANTICRACK)
    // =========================================================================================
    private fun verificarFirma(context: Context) {
        if (BuildConfig.DEBUG) {
            val currentHash = getAppSignatureHash(context)
            Log.e("FIRMA_APP_DEV", "Modo Dev Activo. Evadiendo Anti-Crack. Tu hash temporal es: $currentHash")
            return
        }

        try {
            val currentHash = getAppSignatureHash(context)
            val expectedHash = decryptString(VALID_SIGNATURE_HASH)

            if (currentHash != null && expectedHash.isNotEmpty()) {
                if (currentHash != expectedHash) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
            }
        } catch (e: Exception) {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun getAppSignatureHash(context: Context): String? {
        try {
            val packageName = context.packageName
            val packageManager = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                info.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(signatures[0].toByteArray())
                return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Motor de desencriptación centralizado (KURO)
    private fun decryptString(encryptedHex: String): String {
        return try {
            val bytes = ByteArray(encryptedHex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = encryptedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val base64Encoded = String(bytes)
            val xoredBytes = Base64.decode(base64Encoded, Base64.DEFAULT)

            val key = "KURO".toByteArray()
            val decrypted = ByteArray(xoredBytes.size)
            for (i in xoredBytes.indices) {
                decrypted[i] = (xoredBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            String(decrypted)
        } catch (e: Exception) {
            ""
        }
    }

    private fun cleanupOldCache() {
        val lastCleanup = PreferenceHelper.getLong("last_cache_cleanup", 0L)
        val now = System.currentTimeMillis()
        if (now - lastCleanup > 24 * 60 * 60 * 1000) {
            val cacheDir = File(cacheDir, "exoplayer_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            PreferenceHelper.putLong("last_cache_cleanup", now)
        }
    }

    private fun initializeNotificationChannels() {
        val downloadChannel = NotificationChannelCompat.Builder(
            PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_playlist))
            .setDescription(getString(R.string.enqueue_playlist_description))
            .build()
        val playlistDownloadEnqueueChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_channel_name))
            .setDescription(getString(R.string.download_channel_description))
            .build()
        val playerChannel = NotificationChannelCompat.Builder(
            PLAYER_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.player_channel_name))
            .setDescription(getString(R.string.player_channel_description))
            .build()
        val pushChannel = NotificationChannelCompat.Builder(
            PUSH_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(getString(R.string.push_channel_name))
            .setDescription(getString(R.string.push_channel_description))
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannelsCompat(
            listOf(
                downloadChannel,
                playlistDownloadEnqueueChannel,
                pushChannel,
                playerChannel
            )
        )
    }

    companion object {
        lateinit var instance: LibreTubeApp

        @UnstableApi
        private var simpleCache: SimpleCache? = null

        @UnstableApi
        @Synchronized
        fun getCache(): SimpleCache {
            if (simpleCache == null) {
                val cacheDir = File(instance.cacheDir, "exoplayer_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024) // 500MB max
                val databaseProvider = StandaloneDatabaseProvider(instance)
                simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            }
            return simpleCache!!
        }

        const val DOWNLOAD_CHANNEL_NAME = "download_service"
        const val PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME = "playlist_download_enqueue"
        const val PLAYER_CHANNEL_NAME = "player_mode"
        const val PUSH_CHANNEL_NAME = "notification_worker"
    }
}