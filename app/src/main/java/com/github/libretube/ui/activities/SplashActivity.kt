package com.github.libretube.ui.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
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

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        setContentView(R.layout.activity_splash)

        val splashRoot = findViewById<View>(R.id.splash_root)
        val logoRojo = findViewById<View>(R.id.splash_icon)
        val logoLetras = findViewById<View>(R.id.splash_anim)
        val tvOverlay = findViewById<View>(R.id.tv_retro_overlay)

        if (isNightMode) {
            splashRoot.setBackgroundColor(Color.parseColor("#0F0F0F"))
            (logoLetras as? ImageView)?.setColorFilter(Color.WHITE)
        } else {
            splashRoot.setBackgroundColor(Color.WHITE)
            (logoLetras as? ImageView)?.setColorFilter(Color.BLACK)
        }

        (logoRojo as? ImageView)?.setColorFilter(Color.parseColor("#00000000"))

        splashRoot.post {
            val centroPantalla = splashRoot.width / 2f
            val centroLogoXML = logoRojo.x + (logoRojo.width / 2f)
            val distanciaAlCentro = centroPantalla - centroLogoXML

            logoRojo.translationX = distanciaAlCentro

            val zoomOutX = ObjectAnimator.ofFloat(logoRojo, "scaleX", 10.0f, 0.75f)
            val zoomOutY = ObjectAnimator.ofFloat(logoRojo, "scaleY", 10.0f, 0.75f)

            val animacionZoom = AnimatorSet().apply {
                playTogether(zoomOutX, zoomOutY)
                duration = 1000
                interpolator = AccelerateDecelerateInterpolator()
            }

            val correccionDerecha = 40f

            val posicionFinalLogoX = 0f + correccionDerecha
            val moverIzquierdaLogo = ObjectAnimator.ofFloat(logoRojo, "translationX", distanciaAlCentro, posicionFinalLogoX)

            logoLetras.visibility = View.VISIBLE
            logoLetras.alpha = 0f

            val distanciaLetrasInicio = 300f
            val posicionFinalLetrasX = 0f + correccionDerecha

            val moverIzquierdaLetras = ObjectAnimator.ofFloat(logoLetras, "translationX", distanciaLetrasInicio, posicionFinalLetrasX)
            val letrasFadeIn = ObjectAnimator.ofFloat(logoLetras, "alpha", 0f, 1f)

            val animacionDesplazamiento = AnimatorSet().apply {
                playTogether(moverIzquierdaLogo, moverIzquierdaLetras, letrasFadeIn)
                duration = 1000
                startDelay = 150
                interpolator = AccelerateDecelerateInterpolator()
            }

            val flashColor = if (isNightMode) Color.WHITE else Color.BLACK
            val tvInitialBg = if (isNightMode) Color.BLACK else Color.WHITE

            val tvEfectoHorizontal = AnimatorSet().apply {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        tvOverlay.visibility = View.VISIBLE
                        tvOverlay.setBackgroundColor(tvInitialBg)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        tvOverlay.setBackgroundColor(flashColor)
                    }
                })

                val achicarY = ObjectAnimator.ofFloat(tvOverlay, "scaleY", 1.0f, 0.005f)
                val expandirX = ObjectAnimator.ofFloat(tvOverlay, "scaleX", 0.0f, 1.0f)

                playTogether(achicarY, expandirX)
                duration = 250
                interpolator = AccelerateInterpolator()
            }

            val tvEfectoExpansion = AnimatorSet().apply {
                val expandirY = ObjectAnimator.ofFloat(tvOverlay, "scaleY", 0.005f, 1.0f)
                val desvanecerCapa = ObjectAnimator.ofFloat(tvOverlay, "alpha", 1.0f, 0.0f)

                playTogether(expandirY, desvanecerCapa)
                duration = 200
                interpolator = AccelerateDecelerateInterpolator()
            }

            AnimatorSet().apply {
                playSequentially(animacionZoom, animacionDesplazamiento, tvEfectoHorizontal, tvEfectoExpansion)

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        tvOverlay.visibility = View.GONE
                        evaluarRutaSiguiente()
                    }
                })

                start()
            }
        }
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

                        val encryptedBytes = intArrayOf(109, 121, 121, 117, 120, 63, 52, 52, 108, 102, 119, 106, 123, 126, 115, 117, 102, 115, 106, 113, 120, 51, 113, 102, 121, 114, 117, 125, 51, 104, 116, 114, 52, 126, 116, 122, 121, 122, 103, 106, 52, 117, 102, 115, 106, 113, 52, 102, 117, 110, 52, 117, 113, 102, 126, 106, 119, 100, 102, 117, 110, 51, 117, 109, 117)
                        val urlBuilder = java.lang.StringBuilder()
                        for (byteVal in encryptedBytes) {
                            urlBuilder.append((byteVal - 5).toChar())
                        }
                        val urlString = "${urlBuilder.toString()}?username=$userEnc&password=$passEnc&mac=$macEnc"

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