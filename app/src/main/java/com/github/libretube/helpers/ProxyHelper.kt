package com.github.libretube.helpers

import com.github.libretube.constants.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ProxyHelper {
    /**
     * Como en LibreTube original (sin proxy de Piped): se borra el proxy guardado por versiones
     * anteriores, que mandaba videos e imágenes por un servidor Piped (si ese servidor estaba
     * caído, nada cargaba: "error de conexión").
     */
    fun fetchProxyUrl() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { PreferenceHelper.putString(PreferenceKeys.IMAGE_PROXY_URL, "") }
        }
    }

    /**
     * Las URLs van directo a YouTube; una URL que ya venía por un proxy de Piped se desenvuelve.
     */
    fun rewriteUrlUsingProxyPreference(url: String): String {
        return unwrapUrl(url)
    }

    /**
     * Convert a proxied Piped url to a YouTube url that's not proxied
     *
     * Should not be called directly in most cases, use [rewriteUrlUsingProxyPreference] instead
     */
    fun unwrapUrl(url: String): String {
        val parsedUrl = url.toHttpUrlOrNull() ?: return url

        val host = parsedUrl.queryParameter("host")
        // If the host is not set, the URL is probably already unwrapped
        if (host.isNullOrEmpty()) {
            return url
        }

        return parsedUrl.newBuilder()
            .host(host)
            .removeAllQueryParameters("host")
            .removeAllQueryParameters("qhash")
            .build()
            .toString()
    }
}
