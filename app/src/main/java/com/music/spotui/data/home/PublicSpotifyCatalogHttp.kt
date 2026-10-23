package com.music.spotui.data.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Spotify may remove the locale prefix. Follow only redirects to the same public entity. */
internal class PublicSpotifyCatalogHttp(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(12, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun read(path: String): JSONObject = withContext(Dispatchers.IO) {
        var url = "https://open.spotify.com$path".toHttpUrl()
        val identity = resource(url) ?: error("Invalid public catalog path")
        val visited = mutableSetOf<HttpUrl>()
        repeat(4) {
            coroutineContext.ensureActive()
            check(visited.add(url)) { "Public catalog redirect loop" }
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8").build()
            client.newCall(request).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve)
                    check(next != null && resource(next) == identity) { "Public catalog redirect changed identity" }
                    url = next
                } else {
                    check(response.isSuccessful) { "Public catalog HTTP ${response.code}" }
                    check(response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                        "Public catalog is not HTML"
                    }
                    val output = ByteArrayOutputStream()
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 4 * 1024 * 1024) { "Catalog page too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    coroutineContext.ensureActive()
                    return@withContext PublicSpotifyHomeParser.page(output.toString("UTF-8"))
                        ?: error("Public catalog anonymous state missing")
                }
            }
        }
        error("Too many public catalog redirects")
    }

    private fun resource(url: HttpUrl): String? {
        if (url.scheme != "https" || url.host != "open.spotify.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val path = url.encodedPath.replaceFirst(Regex("^/intl-[A-Za-z-]+(?=/|$)"), "").trimEnd('/')
        if (path.isEmpty()) return "home"
        return path.takeIf { Regex("/(track|artist|playlist)/[A-Za-z0-9]{22}").matches(it) }
    }
}
