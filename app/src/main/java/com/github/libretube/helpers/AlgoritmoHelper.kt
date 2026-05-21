package com.github.libretube.helpers

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AlgoritmoHelper {

    private const val BINARY_URL = "01101000011101000111010001110000011100110011101000101111001011110110011101100001011100100110010101110110011110010110111001110000011000010110111001100101011011000111001100101110011011000110000101110100011011010111000001111000001011100110001101101111011011010010111101111001011011110111010101110100011101010110001001100101001011110111000001100001011011100110010101101100001011110110000101110000011010010010111101100001011011000110011101101111011100100110100101110100011011010110111100101110011100000110100001110000"

    private fun decodeBinary(binary: String): String {
        return try {
            val clean = binary.replace("\\s+".toRegex(), "")
            val chars = clean.chunked(8).map { it.toInt(2).toChar() }.toCharArray()
            String(chars)
        } catch (e: Exception) {
            ""
        }
    }

    private fun getUserId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId.isNullOrBlank()) "user_anonimo" else "device_$androidId"
        } catch (e: Exception) {
            "user_desconocido"
        }
    }

    // AHORA LIMPIA TAMBIÉN POR COMAS
    private fun extraerKeywordDelTitulo(title: String?): String {
        if (title.isNullOrBlank()) return "tendencias"

        // Cortar el título por el guion "-", la barra "|" o la coma ","
        val artistPart = title.split("-", "|", ",").firstOrNull() ?: title

        var clean = artistPart.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        clean = clean.replace(Regex("(?i)\\b(official|video|music|audio|lyric|hd|4k|mv|live|oficial|ft|feat)\\b"), " ")
        clean = clean.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s]"), " ")

        val words = clean.trim().split(Regex("\\s+")).filter { it.length > 2 }.take(3)
        return if (words.isNotEmpty()) words.joinToString(" ") else "tendencias"
    }

    suspend fun registrarVisita(context: Context, title: String?) = withContext(Dispatchers.IO) {
        try {
            val validKeyword = extraerKeywordDelTitulo(title)
            if (validKeyword == "tendencias") return@withContext

            val baseUrl = decodeBinary(BINARY_URL)
            if (baseUrl.isBlank()) return@withContext

            val username = getUserId(context)
            val encodedKeyword = URLEncoder.encode(validKeyword, "UTF-8")

            val requestUrl = "$baseUrl?action=registrar&username=$username&keyword=$encodedKeyword"

            val conn = URL(requestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 LibreTube-App")
            conn.doInput = true

            conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
        } catch (e: Exception) {}
    }

    suspend fun obtenerRecomendacion(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val baseUrl = decodeBinary(BINARY_URL)
            if (baseUrl.isBlank()) return@withContext "tendencias"

            val username = getUserId(context)

            val requestUrl = "$baseUrl?action=obtener&username=$username"

            val conn = URL(requestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 LibreTube-App")
            conn.doInput = true

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            return@withContext json.optString("recomendacion", "tendencias")
        } catch (e: Exception) {
            return@withContext "tendencias"
        }
    }
}