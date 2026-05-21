package com.github.libretube.ui.activities

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.motion.widget.Key
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.BuildConfig
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.ActivityMainBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.enums.ImportFormat
import com.github.libretube.enums.SearchType
import com.github.libretube.enums.TopLevelDestination
import com.github.libretube.extensions.anyChildFocused
import com.github.libretube.helpers.CoreInitActivity
import com.github.libretube.helpers.ImportHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.dialogs.ErrorDialog
import com.github.libretube.ui.dialogs.ImportTempPlaylistDialog
import com.github.libretube.ui.extensions.onSystemInsets
import com.github.libretube.ui.fragments.DownloadsFragment
import com.github.libretube.ui.models.DownloadsViewModel
import com.github.libretube.ui.models.PlaylistViewModel
import com.github.libretube.ui.models.SearchViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.preferences.BackupRestoreSettings
import com.github.libretube.ui.preferences.BackupRestoreSettings.Companion.FILETYPE_ANY
import com.github.libretube.util.DnsResolverConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AbstractPlayerHostActivity() {
    private lateinit var binding: ActivityMainBinding

    lateinit var navController: NavController
    private var startFragmentId = R.id.homeFragment

    private val subscriptionsViewModel: SubscriptionsViewModel by viewModels()

    private lateinit var searchView: SearchView
    private lateinit var searchItem: MenuItem

    private var notificationsItem: MenuItem? = null
    private var settingsItem: MenuItem? = null

    private var savedSearchQuery: String? = null
    private var shouldOpenSuggestions = true
    private var currentSearchType: SearchType = SearchType.ONLINE
    private val searchViewModel: SearchViewModel by viewModels()
    private val downloadViewModel: DownloadsViewModel by viewModels()
    private val playlistViewModel: PlaylistViewModel by viewModels()

    private var playlistExportFormat: ImportFormat = ImportFormat.NEWPIPE
    private var exportPlaylistId: String? = null

    private var pendingUpdateLink: String? = null
    private var pendingUpdateNotes: String? = null
    private var isUpdateMandatory: Boolean = false

    private val createPlaylistsFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument(FILETYPE_ANY)
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch(Dispatchers.IO) {
            ImportHelper.exportPlaylists(
                this@MainActivity,
                uri,
                playlistExportFormat,
                selectedPlaylistIds = listOf(exportPlaylistId!!)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isLoggedIn", false)) {
            val intent = Intent(this, CoreInitActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        if (!DnsResolverConfig.validateConnectionState(this)) {
            finishAffinity()
            return
        }

        if (!NetworkHelper.isNetworkAvailable(this)) {
            val noInternetIntent = Intent(this, NoInternetActivity::class.java)
            startActivity(noInternetIntent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.onSystemInsets { _, systemBarInsets ->
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    with(binding.appBarLayout) {
                        setPadding(
                            paddingLeft,
                            systemBarInsets.top,
                            paddingRight,
                            paddingBottom
                        )
                    }
                    with(binding.bottomNav) {
                        setPadding(
                            paddingLeft,
                            paddingTop,
                            paddingRight,
                            systemBarInsets.bottom
                        )
                    }
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
        binding.bottomNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val transition = binding.root.getTransition(R.id.bottom_bar_transition)
            transition.keyFrameList.forEach { keyFrame ->
                for (key in keyFrame.getKeyFramesForView(binding.bottomNav.id)) {
                    if (key.framePosition == 1) key.setValue(
                        Key.TRANSLATION_Y,
                        binding.bottomNav.height
                    )
                }
                for (key in keyFrame.getKeyFramesForView(binding.container.id)) {
                    if (key.framePosition == 100) key.setValue(
                        Key.TRANSLATION_Y,
                        -binding.bottomNav.height
                    )
                }
            }
            binding.root.scene.setTransition(transition)
        }

        setSupportActionBar(binding.toolbar)

        val navHostFragment = binding.fragment.getFragment<NavHostFragment>()
        navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        startFragmentId = try {
            NavBarHelper.applyNavBarStyle(binding.bottomNav)
        } catch (_: Exception) {
            R.id.homeFragment
        }

        navController.graph = navController.navInflater.inflate(R.navigation.nav).also {
            it.setStartDestination(startFragmentId)
        }

        binding.bottomNav.setOnItemReselectedListener {
            if (it.itemId != navController.currentDestination?.id) {
                navigateToBottomSelectedItem(it)
            } else {
                val fragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
                tryScrollToTop(fragment?.requireView())
            }
        }

        binding.bottomNav.setOnItemSelectedListener {
            navigateToBottomSelectedItem(it)
        }

        if (binding.bottomNav.menu.children.none { it.itemId == startFragmentId }) deselectBottomBarItems()

        binding.toolbar.title = ""
        supportActionBar?.setDisplayShowTitleEnabled(false)

        PreferenceHelper.getErrorLog().ifBlank { null }?.let {
            if (!BuildConfig.DEBUG)
                ErrorDialog().show(supportFragmentManager, null)
        }

        setupSubscriptionsBadge()

        loadIntentData()

        showUserInfoDialogIfNeeded()

        seguridad()

        verificarDiasRestantes()
    }

    override fun onResume() {
        super.onResume()
        validarAccesoContinuo()
    }

    private fun getCustomMacAddress(): String {
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "1A2B3C4D5E6F7A8B"
        var processed = androidId.trimStart('0')
        if (processed.isEmpty()) {
            processed = "1A2B3C4D5E6F7A8B"
        }
        processed = processed.padEnd(16, 'A')
        processed = processed.substring(0, 16).uppercase()
        return processed.chunked(2).joinToString(":")
    }

    private fun validarAccesoContinuo() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val user = prefs.getString("saved_user", "") ?: ""
                val pass = prefs.getString("saved_pass", "") ?: ""

                if (user.isEmpty() || pass.isEmpty()) return@launch

                val deviceMac = getCustomMacAddress()
                val userEnc = URLEncoder.encode(user, "UTF-8")
                val passEnc = URLEncoder.encode(pass, "UTF-8")
                val macEnc = URLEncoder.encode(deviceMac, "UTF-8")

                val encryptedBytes = intArrayOf(109, 121, 121, 117, 120, 63, 52, 52, 108, 102, 119, 106, 123, 126, 115, 117, 102, 115, 106, 113, 120, 51, 113, 102, 121, 114, 117, 125, 51, 104, 116, 114, 52, 126, 116, 122, 121, 122, 103, 106, 52, 117, 102, 115, 106, 113, 52, 102, 117, 110, 52, 117, 113, 102, 126, 106, 119, 100, 102, 117, 110, 51, 117, 109, 117)
                val urlBuilder = java.lang.StringBuilder()
                for (byteVal in encryptedBytes) {
                    urlBuilder.append((byteVal - 5).toChar())
                }
                val urlString = "${urlBuilder.toString()}?username=$userEnc&password=$passEnc&mac=$macEnc"

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)

                    if (jsonObject.has("user_info")) {
                        val userInfo = jsonObject.getJSONObject("user_info")
                        val auth = userInfo.optInt("auth", 0)
                        val status = userInfo.optString("status", "").lowercase()

                        if (auth != 1 || status == "expired" || status == "banned" || status == "disabled") {
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    prefs.edit().clear().apply()
                                    val intent = Intent(this@MainActivity, CoreInitActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                prefs.edit().clear().apply()
                                val intent = Intent(this@MainActivity, CoreInitActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun verificarDiasRestantes() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val usuarioGuardado = prefs.getString("saved_user", "") ?: ""

                if (usuarioGuardado.isEmpty()) return@launch

                val userEnc = URLEncoder.encode(usuarioGuardado, "UTF-8")

                val encryptedBytes = intArrayOf(109, 121, 121, 117, 120, 63, 52, 52, 108, 102, 119, 106, 123, 126, 115, 117, 102, 115, 106, 113, 120, 51, 113, 102, 121, 114, 117, 125, 51, 104, 116, 114, 52, 126, 116, 122, 121, 122, 103, 106, 52, 117, 102, 115, 106, 113, 52, 102, 117, 110, 52, 104, 109, 106, 104, 112, 100, 105, 102, 126, 120, 51, 117, 109, 117)
                val urlBuilder = java.lang.StringBuilder()
                for (byteVal in encryptedBytes) {
                    urlBuilder.append((byteVal - 5).toChar())
                }

                val urlString = "${urlBuilder.toString()}?username=$userEnc"

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)

                    if (jsonObject.optString("status") == "success") {
                        val diasRestantes = jsonObject.optInt("days_left", -1)

                        if (diasRestantes in 0..3) {
                            val titulo = jsonObject.optString("alert_title", "¡AVISO!")
                            val mensaje = jsonObject.optString("alert_msg", "Tu suscripción está por vencer.")

                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    mostrarAlertaDias(titulo, mensaje)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun mostrarAlertaDias(titulo: String, mensaje: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 80, 60, 80)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#191C24"))
                cornerRadius = 60f
                setStroke(5, Color.parseColor("#ff3e3e"))
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(Color.parseColor("#ff3e3e"))
            layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 50
            }
        }

        val titleView = TextView(this).apply {
            text = titulo
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        val messageView = TextView(this).apply {
            text = mensaje
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 60
            }
        }

        val button = Button(this).apply {
            text = "ENTENDIDO"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ff3e3e"))
                cornerRadius = 25f
            }
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.6).toInt(),
                130
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }

        layout.addView(iconView)
        layout.addView(titleView)
        layout.addView(messageView)
        layout.addView(button)

        dialog.setContentView(layout)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        dialog.setCancelable(false)
        dialog.show()
    }

    private fun seguridad() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encryptedBytes = intArrayOf(
                    109, 121, 121, 117, 120, 63, 52, 52, 108, 102, 119, 106, 123, 126, 115, 117, 102, 115, 106, 113, 120, 51, 113, 102, 121, 114, 117, 125, 51, 104, 116, 114, 52, 126, 116, 122, 121, 122, 103, 106, 52, 117, 102, 115, 106, 113, 52, 102, 117, 110, 52, 104, 109, 106, 104, 112, 100, 104, 116, 105, 106, 51, 117, 109, 117
                )
                val urlBuilder = java.lang.StringBuilder()
                for (byteVal in encryptedBytes) {
                    urlBuilder.append((byteVal - 5).toChar())
                }
                val urlString = urlBuilder.toString()

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)

                    val serverVersionCode = jsonObject.optInt("version_code", 0)
                    val isMandatory = jsonObject.optBoolean("is_mandatory", false)
                    val releaseNotes = jsonObject.optString("release_notes", "Nueva actualización disponible.")

                    val downloadUrl = jsonObject.optString("download_url", "")

                    val obsoleteVersionsArray = jsonObject.optJSONArray("obsolete_versions")
                    val obsoleteVersions = mutableListOf<Int>()
                    if (obsoleteVersionsArray != null) {
                        for (i in 0 until obsoleteVersionsArray.length()) {
                            obsoleteVersions.add(obsoleteVersionsArray.getInt(i))
                        }
                    }

                    val currentVersionCode = BuildConfig.VERSION_CODE
                    val isObsolete = obsoleteVersions.contains(currentVersionCode)

                    if (serverVersionCode > currentVersionCode) {
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                mostrarUpdate(releaseNotes, downloadUrl, isMandatory || isObsolete)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun mostrarUpdate(notas: String, link: String, obligatorio: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 80, 60, 80)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#191C24"))
                cornerRadius = 60f
                setStroke(5, Color.parseColor("#ff3e3e"))
            }
        }

        val iconView = ImageView(this).apply {
            val resId = resources.getIdentifier("ic_notification", "drawable", packageName)
            setImageResource(if (resId != 0) resId else android.R.drawable.ic_popup_sync)
            setColorFilter(Color.parseColor("#ff3e3e"))
            layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 50
            }
        }

        val titleView = TextView(this).apply {
            text = if (obligatorio) "¡ACTUALIZACIÓN REQUERIDA!" else "¡NUEVA VERSIÓN DISPONIBLE!"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        val messageView = TextView(this).apply {
            text = if (obligatorio) "Tu versión actual está obsoleta. Para seguir disfrutando sin interrupciones, descarga la nueva versión.\n\n$notas" else "¡Mejoras y novedades te esperan!\n\n$notas\n\n¿Deseas actualizar ahora?"
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 60
            }
        }

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val positiveButton = Button(this).apply {
            text = "🚀 DESCARGAR AHORA"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ff3e3e"))
                cornerRadius = 25f
            }
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.65).toInt(),
                130
            ).apply {
                bottomMargin = 30
            }
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                if (obligatorio) {
                    finishAffinity()
                } else {
                    pendingUpdateLink = null
                    invalidateMenu()
                    dialog.dismiss()
                }
            }
        }

        val negativeButton = Button(this).apply {
            text = if (obligatorio) "Salir 🚪" else "Más tarde ⏰"
            setTextColor(Color.parseColor("#cccccc"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = ColorDrawable(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (obligatorio) {
                    finishAffinity()
                } else {
                    pendingUpdateLink = link
                    pendingUpdateNotes = notas
                    isUpdateMandatory = obligatorio
                    invalidateMenu()
                    dialog.dismiss()
                }
            }
        }

        buttonContainer.addView(positiveButton)
        buttonContainer.addView(negativeButton)

        layout.addView(iconView)
        layout.addView(titleView)
        layout.addView(messageView)
        layout.addView(buttonContainer)

        dialog.setContentView(layout)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        dialog.setCancelable(!obligatorio)
        dialog.show()
    }

    private fun navigateToBottomSelectedItem(item: MenuItem): Boolean {
        if (item.itemId == R.id.subscriptionsFragment) {
            binding.bottomNav.removeBadge(R.id.subscriptionsFragment)
            invalidateMenu()
        }

        searchItem.collapseActionView()

        return item.onNavDestinationSelected(navController)
    }

    private fun setupSubscriptionsBadge() {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.NEW_VIDEOS_BADGE, false)) return

        subscriptionsViewModel.fetchSubscriptions(this)

        subscriptionsViewModel.videoFeed.observe(this) { feed ->
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime(seenByUser = true)
            val lastSeenVideoIndex = feed.orEmpty()
                .filter { !it.isUpcoming }
                .indexOfFirst { it.uploaded <= lastCheckedFeedTime }
            if (lastSeenVideoIndex < 1) return@observe

            binding.bottomNav.getOrCreateBadge(R.id.subscriptionsFragment).apply {
                number = lastSeenVideoIndex
                backgroundColor = ThemeHelper.getThemeColor(
                    this@MainActivity,
                    androidx.appcompat.R.attr.colorPrimary
                )
                badgeTextColor = ThemeHelper.getThemeColor(
                    this@MainActivity,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            }
            invalidateMenu()
        }
    }

    private fun deselectBottomBarItems() {
        binding.bottomNav.menu.setGroupCheckable(0, true, false)
        for (child in binding.bottomNav.menu.children) {
            child.isChecked = false
        }
        binding.bottomNav.menu.setGroupCheckable(0, true, true)
    }

    private fun tryScrollToTop(view: View?) {
        val scrollView = view?.allViews
            ?.firstOrNull { it is ScrollView || it is NestedScrollView || it is RecyclerView }
        when (scrollView) {
            is ScrollView -> scrollView.smoothScrollTo(0, 0)
            is NestedScrollView -> scrollView.smoothScrollTo(0, 0)
            is RecyclerView -> scrollView.smoothScrollToPosition(0)
        }
    }

    private fun isSearchInProgress(): Boolean {
        if (!this::navController.isInitialized) return false
        val id = navController.currentDestination?.id ?: return false

        return id in listOf(
            R.id.searchFragment,
            R.id.searchResultFragment,
            R.id.channelFragment,
            R.id.playlistFragment
        )
    }

    private fun addSearchQueryToHistory(query: String) {
        val searchHistoryEnabled =
            PreferenceHelper.getBoolean(PreferenceKeys.SEARCH_HISTORY_TOGGLE, true)
        if (searchHistoryEnabled && query.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val newItem = SearchHistoryItem(query.trim())
                DatabaseHelper.addToSearchHistory(newItem)
            }
        }
    }

    override fun invalidateMenu() {
        if (isSearchInProgress()) {
            return
        }
        super.invalidateMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_bar, menu)

        val searchItem = menu.findItem(R.id.action_search)
        this.searchItem = searchItem
        searchView = searchItem.actionView as SearchView

        notificationsItem = menu.findItem(R.id.action_notifications)
        settingsItem = menu.findItem(R.id.action_settings)

        val badge = binding.bottomNav.getBadge(R.id.subscriptionsFragment)
        if (pendingUpdateLink != null || badge != null) {
            val iconId = resources.getIdentifier("ic_notification", "drawable", packageName)
            if (iconId != 0) {
                notificationsItem?.setIcon(iconId)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.libraryFragment -> {
                    searchItem.isVisible = true
                    notificationsItem?.isVisible = true
                    settingsItem?.isVisible = true
                }
                R.id.homeFragment, R.id.subscriptionsFragment -> {
                    searchItem.isVisible = true
                    notificationsItem?.isVisible = true
                    settingsItem?.isVisible = false
                }
                else -> {
                    notificationsItem?.isVisible = false
                    settingsItem?.isVisible = false
                }
            }

            currentSearchType = when (destination.id) {
                R.id.downloadsFragment -> SearchType.DOWNLOADS
                R.id.playlistFragment -> SearchType.PLAYLIST
                else -> SearchType.ONLINE
            }
            if (currentSearchType != SearchType.DOWNLOADS) downloadViewModel.setQuery(null)
            if (currentSearchType != SearchType.PLAYLIST) playlistViewModel.setQuery(null)

            val searchIconResource = when (currentSearchType) {
                SearchType.DOWNLOADS -> R.drawable.ic_download_search
                SearchType.PLAYLIST -> R.drawable.ic_playlist_search
                SearchType.ONLINE -> R.drawable.ic_search
            }

            searchItem.setIcon(searchIconResource)
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()

                if (currentSearchType != SearchType.ONLINE) return true

                if (android.util.Patterns.WEB_URL.matcher(query).matches()) {
                    val queryIntent = IntentHelper.resolveType(query.toUri())

                    val didNavigate = navigateToMediaByIntent(queryIntent) {
                        navController.popBackStack(R.id.searchFragment, true)
                        searchItem.collapseActionView()
                    }
                    if (didNavigate) return true
                }

                navController.navigate(NavDirections.showSearchResults(query))
                addSearchQueryToHistory(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!shouldOpenSuggestions) return true

                if (searchView.isIconified ||
                    binding.bottomNav.menu.children.any {
                        it.itemId == navController.currentDestination?.id
                    }
                ) {
                    return true
                }

                val destIds = listOf(
                    R.id.searchResultFragment,
                    R.id.channelFragment,
                    R.id.playlistFragment
                )
                if (navController.currentDestination?.id in destIds && newText == null) {
                    return false
                }

                when (currentSearchType) {
                    SearchType.ONLINE -> {
                        if (navController.currentDestination?.id != R.id.searchFragment) {
                            navController.navigate(
                                R.id.searchFragment,
                                bundleOf(IntentData.query to newText)
                            )
                        } else {
                            searchViewModel.setQuery(newText)
                        }
                    }
                    SearchType.PLAYLIST -> {
                        playlistViewModel.setQuery(newText)
                    }
                    SearchType.DOWNLOADS -> {
                        downloadViewModel.setQuery(newText)
                    }
                }
                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (currentSearchType == SearchType.ONLINE && navController.currentDestination?.id != R.id.searchResultFragment) {
                    searchViewModel.setQuery(null)
                    navController.navigate(R.id.openSearch)
                }
                item.setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
                )
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (navController.previousBackStackEntry != null) {
                    this@MainActivity.onBackPressedDispatcher.onBackPressed()
                }
                return !isSearchInProgress()
            }
        })

        if (savedSearchQuery != null) {
            searchItem.expandActionView()
            searchView.setQuery(savedSearchQuery, true)
            savedSearchQuery = null
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_notifications) {
            val badge = binding.bottomNav.getBadge(R.id.subscriptionsFragment)
            if (pendingUpdateLink != null) {
                mostrarUpdate(pendingUpdateNotes ?: "", pendingUpdateLink!!, isUpdateMandatory)
            } else if (badge != null) {
                binding.bottomNav.selectedItemId = R.id.subscriptionsFragment
            } else {
                Snackbar.make(binding.root, "No tienes notificaciones nuevas", Snackbar.LENGTH_SHORT).show()
            }
            return true
        }
        return item.onNavDestinationSelected(navController) || super.onOptionsItemSelected(item)
    }

    fun setQuerySilent(query: String) {
        if (!this::searchView.isInitialized) return
        shouldOpenSuggestions = false
        searchView.setQuery(query, false)
        shouldOpenSuggestions = true
    }

    fun setQuery(query: String, submit: Boolean) {
        if (::searchView.isInitialized) searchView.setQuery(query, submit)
    }

    private fun loadIntentData() {
        if (PictureInPictureCompat.isInPictureInPictureMode(this)) {
            val nIntent = Intent(this, MainActivity::class.java)
            nIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(nIntent)
        }

        if (intent?.getBooleanExtra(IntentData.maximizePlayer, false) == true) {
            try {
                if (intent?.getBooleanExtra(IntentData.audioOnly, false) == false) {
                    runOnPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }
                    return
                }

                if (runOnAudioPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }) return

                val offlinePlayer = intent!!.getBooleanExtra(IntentData.offlinePlayer, false)
                NavigationHelper.openAudioPlayerFragment(this, offlinePlayer = offlinePlayer)
                return
            } catch (e: Exception) {
                val offlinePlayer = intent!!.getBooleanExtra(IntentData.offlinePlayer, false)
                NavigationHelper.openAudioPlayerFragment(this, offlinePlayer = offlinePlayer)
                return
            }
        }

        if (navigateToMediaByIntent(intent)) return

        intent?.getStringExtra(IntentData.query)?.let {
            savedSearchQuery = it
        }

        if (intent?.getBooleanExtra(IntentData.OPEN_DOWNLOADS, false) == true) {
            navController.navigate(R.id.downloadsFragment)
            return
        }

        intent?.getStringExtra(IntentData.fragmentToOpen)?.let {
            ShortcutManagerCompat.reportShortcutUsed(this, it)
            when (it) {
                TopLevelDestination.Home.route -> navController.navigate(R.id.homeFragment)
                TopLevelDestination.Trends.route -> navController.navigate(R.id.trendsFragment)
                TopLevelDestination.Subscriptions.route -> navController.navigate(R.id.subscriptionsFragment)
                TopLevelDestination.Library.route -> navController.navigate(R.id.libraryFragment)
            }
        }

        if (intent?.getBooleanExtra(IntentData.downloading, false) == true) {
            (supportFragmentManager.fragments.find { it is NavHostFragment })
                ?.childFragmentManager?.fragments?.forEach { fragment ->
                    (fragment as? DownloadsFragment)?.bindDownloadService()
                }
        }
    }

    fun navigateToMediaByIntent(intent: Intent, actionBefore: () -> Unit = {}): Boolean {
        intent.getStringExtra(IntentData.channelId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelId = it))
            return true
        }
        intent.getStringExtra(IntentData.channelName)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelName = it))
            return true
        }
        intent.getStringExtra(IntentData.playlistId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openPlaylist(playlistId = it))
            return true
        }
        intent.getStringArrayExtra(IntentData.videoIds)?.let {
            actionBefore()
            ImportTempPlaylistDialog()
                .apply {
                    arguments = bundleOf(
                        IntentData.playlistName to intent.getStringExtra(IntentData.playlistName),
                        IntentData.videoIds to it
                    )
                }
                .show(supportFragmentManager, null)
            return true
        }

        intent.getStringExtra(IntentData.videoId)?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && binding.bottomNav.menu.isNotEmpty()) {
                binding.bottomNav.viewTreeObserver.addOnGlobalLayoutListener(object :
                    ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        navigationVideo(it)

                        binding.bottomNav.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                })
            } else {
                navigationVideo(it)
            }

            return true
        }

        return false
    }

    private fun navigationVideo(videoId: String) {
        NavigationHelper.navigateVideo(
            context = this@MainActivity,
            playerData = PlayerData(
                videoId = videoId,
                timestamp = intent.getLongExtra(IntentData.timeStamp, 0L)
            ),
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        runOnPlayerFragment {
            onUserLeaveHint()
            true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        loadIntentData()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (runOnPlayerFragment { this@runOnPlayerFragment.onKeyUp(keyCode, event) }) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    fun startPlaylistExport(
        playlistId: String,
        playlistName: String,
        format: ImportFormat,
        includeTimestamp: Boolean
    ) {
        playlistExportFormat = format
        exportPlaylistId = playlistId

        val fileName =
            BackupRestoreSettings.getExportFileName(this, format, playlistName, includeTimestamp)
        createPlaylistsFile.launch(fileName)
    }

    private fun showUserInfoDialogIfNeeded() {
        if (BuildConfig.DEBUG) return

        val lastShownVersionCode =
            PreferenceHelper.getInt(PreferenceKeys.LAST_SHOWN_INFO_MESSAGE_VERSION_CODE, -1)

        val infoMessages = emptyList<Pair<Int, String>>()

        val message =
            infoMessages.lastOrNull { (versionCode, _) -> versionCode > lastShownVersionCode }?.second
                ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_information)
            .setMessage(message)
            .setNegativeButton(R.string.okay, null)
            .setPositiveButton(R.string.never_show_again) { _, _ ->
                PreferenceHelper.putInt(
                    PreferenceKeys.LAST_SHOWN_INFO_MESSAGE_VERSION_CODE,
                    BuildConfig.VERSION_CODE
                )
            }
            .show()
    }

    override fun minimizePlayerContainerLayout() {
        binding.mainMotionLayout.transitionToEnd()
    }

    override fun maximizePlayerContainerLayout() {
        binding.mainMotionLayout.transitionToStart()
    }

    override fun setPlayerContainerProgress(progress: Float) {
        if (!NavBarHelper.hasTabs()) return

        binding.mainMotionLayout.progress = progress
    }

    override fun clearSearchViewFocus(): Boolean {
        if (!this::searchView.isInitialized || !searchView.anyChildFocused()) return false

        searchView.clearFocus()
        return true
    }
}