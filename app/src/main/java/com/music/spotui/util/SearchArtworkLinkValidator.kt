package com.music.spotui.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Checks the exact review link on IO before sending it. This never grants artwork. */
internal class SearchArtworkLinkValidator(private val client: OkHttpClient = imageClient) {
    fun isAvailable(url: String): Boolean {
        if (SearchArtworkPermissions.imageUrl(url) != url) return false
        return runCatching {
            client.newCall(Request.Builder().url(url)
                .header("Range", "bytes=0-31")
                .header("Accept", "image/*")
                .header("User-Agent", "SpotifyKosherApp/1.0")
                .build()).execute().use { response ->
                if (response.code != 200 && response.code != 206) return@use false
                // A redirect could review different artwork than the exact URL being approved.
                if (response.request.url.toString() != url) return@use false
                val body = response.body ?: return@use false
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: return@use false
                val bytes = ByteArray(32)
                val count = body.byteStream().use { input ->
                    var read = 0
                    while (read < bytes.size) {
                        val next = input.read(bytes, read, bytes.size - read)
                        if (next < 0) break
                        read += next
                    }
                    read
                }
                fun starts(vararg signature: Int) = count >= signature.size && signature.indices.all { (bytes[it].toInt() and 255) == signature[it] }
                fun ascii(from: Int, until: Int) = if (count >= until) String(bytes, from, until - from, Charsets.US_ASCII) else ""
                when (type) {
                    "image/jpeg" -> starts(0xff, 0xd8, 0xff)
                    "image/png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    "image/gif" -> ascii(0, 6) in setOf("GIF87a", "GIF89a")
                    "image/webp" -> ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP"
                    "image/avif" -> ascii(4, 8) == "ftyp" && ascii(8, 12) in setOf("avif", "avis")
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    companion object {
        private val imageClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
    }
}
