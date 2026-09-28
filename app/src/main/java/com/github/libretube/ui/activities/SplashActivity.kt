package com.github.libretube.ui.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.helpers.CoreInitActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        setContentView(R.layout.activity_splash)

        val splashRoot = findViewById<View>(R.id.splash_root)
        val logoRojo = findViewById<View>(R.id.splash_icon)
        val logoLetras = findViewById<View>(R.id.splash_anim)
        val tvOverlay = findViewById<View>(R.id.tv_retro_overlay)

        tvOverlay?.visibility = View.GONE

        if (isNightMode) {
            splashRoot.setBackgroundColor(Color.parseColor("#0F0F0F"))
            (logoLetras as? ImageView)?.setColorFilter(Color.WHITE)
        } else {
            splashRoot.setBackgroundColor(Color.WHITE)
            (logoLetras as? ImageView)?.setColorFilter(Color.BLACK)
        }

        (logoRojo as? ImageView)?.setColorFilter(Color.parseColor("#00000000"))

        splashRoot.post {
            val screenWidth = splashRoot.width.toFloat()

            // ESCALAS INDEPENDIENTES PARA IGUALAR TAMAÑOS VISUALES
            val iconScale = 0.75f
            val textScale = 0.55f // Letras reducidas para estar al nivel del logo rojo

            val spacing = 5f

            // Anchuras visuales calculadas con las escalas individuales
            val iconVisualWidth = logoRojo.width * iconScale
            val textVisualWidth = logoLetras.width * textScale

            // 1. MATEMÁTICA PURA: Calculamos el ancho del grupo y el centro
            val totalWidth = iconVisualWidth + spacing + textVisualWidth
            val blockStartX = (screenWidth - totalWidth) / 2f

            // 2. Calculamos dónde deben estar los CENTROS de cada imagen en modo Expandido
            val targetIconCenterX = blockStartX + (iconVisualWidth / 2f)
            val targetTextCenterX = blockStartX + iconVisualWidth + spacing + (textVisualWidth / 2f)

            // 3. Obtenemos dónde están los centros originalmente en el XML
            val originalIconCenterX = logoRojo.x + (logoRojo.width / 2f)
            val originalTextCenterX = logoLetras.x + (logoLetras.width / 2f)

            // 4. Calculamos desplazamientos
            val targetIconX = targetIconCenterX - originalIconCenterX
            val targetTextX = targetTextCenterX - originalTextCenterX

            // Posición inicial: El logo rojo cae primero exactamente en el medio de la pantalla
            val centroPantallaX = screenWidth / 2f
            val distanciaAlCentro = centroPantallaX - originalIconCenterX
            logoRojo.translationX = distanciaAlCentro

            // -- ANIMACIÓN 1: Caída del Logo rojo al centro --
            val zoomOutX = ObjectAnimator.ofFloat(logoRojo, "scaleX", 10.0f, iconScale)
            val zoomOutY = ObjectAnimator.ofFloat(logoRojo, "scaleY", 10.0f, iconScale)

            val animacionZoom = AnimatorSet().apply {
                playTogether(zoomOutX, zoomOutY)
                duration = 1000
                interpolator = AccelerateDecelerateInterpolator()
            }

            // -- ANIMACIÓN 2: Expansión (Logo va a la izq, letras entran desde la der) --
            val moverIzquierdaLogo = ObjectAnimator.ofFloat(logoRojo, "translationX", distanciaAlCentro, targetIconX)

            logoLetras.visibility = View.VISIBLE
            logoLetras.alpha = 0f

            // Asignamos la escala más pequeña a las letras
            logoLetras.scaleX = textScale
            logoLetras.scaleY = textScale

            val startTextX = targetTextX + 150f
            logoLetras.translationX = startTextX

            val moverIzquierdaLetras = ObjectAnimator.ofFloat(logoLetras, "translationX", startTextX, targetTextX)
            val letrasFadeIn = ObjectAnimator.ofFloat(logoLetras, "alpha", 0f, 1f)

            val animacionDesplazamiento = AnimatorSet().apply {
                playTogether(moverIzquierdaLogo, moverIzquierdaLetras, letrasFadeIn)
                duration = 900
                startDelay = 150
                interpolator = AccelerateDecelerateInterpolator()
            }

            // Pausa para que el usuario aprecie el logo completo armado
            val pausaArmado = ValueAnimator.ofFloat(0f, 1f).apply { duration = 700 }

            // -- ANIMACIÓN 3: "Se come las letras" (El logo regresa al centro y las letras desaparecen) --
            val regresarCentroLogo = ObjectAnimator.ofFloat(logoRojo, "translationX", targetIconX, distanciaAlCentro)
            val letrasFadeOut = ObjectAnimator.ofFloat(logoLetras, "alpha", 1f, 0f)

            val animacionComer = AnimatorSet().apply {
                playTogether(regresarCentroLogo, letrasFadeOut)
                duration = 450
                interpolator = AccelerateDecelerateInterpolator()
            }

            // Pausa minúscula para preparar el salto
            val pausaSalto = ValueAnimator.ofFloat(0f, 1f).apply { duration = 150 }

            // -- ANIMACIÓN 4: Splash Final (El logo centrado vuela hacia el espectador) --
            val zoomInFinalX = ObjectAnimator.ofFloat(logoRojo, "scaleX", iconScale, 30.0f)
            val zoomInFinalY = ObjectAnimator.ofFloat(logoRojo, "scaleY", iconScale, 30.0f)
            val fadeOutLogo = ObjectAnimator.ofFloat(logoRojo, "alpha", 1.0f, 0.0f)

            val efectoAcercarse = AnimatorSet().apply {
                playTogether(zoomInFinalX, zoomInFinalY, fadeOutLogo)
                duration = 450
                interpolator = AccelerateInterpolator()
            }

            // Unimos la coreografía completa
            AnimatorSet().apply {
                playSequentially(animacionZoom, animacionDesplazamiento, pausaArmado, animacionComer, pausaSalto, efectoAcercarse)

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        evaluarRutaSiguiente()
                    }
                })

                start()
            }
        }
    }

    private fun generateSecurityToken(user: String, mac: String): String {
        val secretKey = "kuropanchi950125"

        val format = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/Panama")
        }

        val currentHourDate = format.format(Date())
        val stringToHash = "$user$mac$currentHourDate$secretKey"

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stringToHash.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }

    private fun getCustomMacAddress(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "1A2B3C4D5E6F7A8B"
        var processed = androidId.trimStart('0')
        if (processed.isEmpty()) {
            processed = "1A2B3C4D5E6F7A8B"
        }
        processed = processed.padEnd(16, 'A')
        processed = processed.substring(0, 16).uppercase()
        return processed.chunked(2).joinToString(":")
    }

    private fun evaluarRutaSiguiente() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("isLoggedIn", false)

        if (isLoggedIn) {
            lifecycleScope.launch(Dispatchers.IO) {
                val user = prefs.getString("saved_user", "") ?: ""
                val pass = prefs.getString("saved_pass", "") ?: ""
                var isValid = true

                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    try {
                        val deviceMac = getCustomMacAddress()
                        val userEnc = URLEncoder.encode(user, "UTF-8")
                        val passEnc = URLEncoder.encode(pass, "UTF-8")
                        val macEnc = URLEncoder.encode(deviceMac, "UTF-8")

                        val securityToken = generateSecurityToken(user, deviceMac)

                        val encryptedBytes = intArrayOf(109, 121, 121, 117, 120, 63, 52, 52, 108, 102, 119, 106, 123, 126, 115, 117, 102, 115, 106, 113, 120, 51, 113, 102, 121, 114, 117, 125, 51, 104, 116, 114, 52, 126, 116, 122, 121, 122, 103, 106, 52, 117, 102, 115, 106, 113, 52, 102, 117, 110, 52, 117, 113, 102, 126, 106, 119, 100, 102, 117, 110, 51, 117, 109, 117)
                        val urlBuilder = java.lang.StringBuilder()
                        for (byteVal in encryptedBytes) {
                            urlBuilder.append((byteVal - 5).toChar())
                        }

                        val urlString = "${urlBuilder.toString()}?username=$userEnc&password=$passEnc&mac=$macEnc&token=$securityToken"

                        val url = URL(urlString)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000

                        if (connection.responseCode == 200) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonObject = JSONObject(response)
                            if (jsonObject.has("user_info")) {
                                val userInfo = jsonObject.getJSONObject("user_info")
                                val auth = userInfo.optInt("auth", 0)
                                val status = userInfo.optString("status", "").lowercase()

                                if (auth != 1 || status == "expired" || status == "banned" || status == "disabled") {
                                    isValid = false
                                }
                            } else {
                                isValid = false
                            }
                        } else {
                            isValid = false
                        }
                    } catch (e: Exception) {
                        isValid = true
                    }
                } else {
                    isValid = false
                }

                withContext(Dispatchers.Main) {
                    if (isValid) {
                        irAMain()
                    } else {
                        prefs.edit().clear().apply()
                        irALogin()
                    }
                }
            }
        } else {
            irALogin()
        }
    }

    private fun irAMain() {
        val destino = Intent(this, MainActivity::class.java)
        destino.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(destino)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun irALogin() {
        val destino = Intent(this, CoreInitActivity::class.java)
        destino.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(destino)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}