package com.music.spotui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.data.entity.HomeItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Separate from the global URL whitelist. Never register these URLs with allowImageUrl. */
object HomeArtworkPermissions {
    internal data class Decision(val id: String, val url: String, val status: String, val updatedAt: String) {
        val token: String get() = "$status:$updatedAt"
    }
    private var entries by mutableStateOf<Map<String, Decision>>(emptyMap())
    private val revision = MutableStateFlow(0L)
    // Car browsing runs without a Compose frame clock; publish permission changes explicitly.
    internal val updates = revision.asStateFlow()

    internal fun decision(imageKey: String): Decision? = entries.values.singleOrNull { it.id == imageKey }

    fun imageUrl(raw: String): String? {
        val value = raw.trim()
        if (value.length !in 1..2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && uri.host != "localhost" && uri.host.contains('.')
        }
    }

    /** 192-bit digest leaves room for the action prefix within Telegram's 64-byte limit. */
    fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(24).joinToString("") { "%02x".format(it) }

    fun isApproved(url: String): Boolean = imageUrl(url)?.let { entries[it]?.status == "approved" } == true

    fun isAllowedOnHome(item: HomeItem): Boolean =
        isApproved(item.imageUrl) || KosherWhitelistManager.isHomeItemWhitelisted(item)

    /** User-approved expansion: the same exact cover in its Playlist/Radio presentation.
     * This does not register the URL globally or affect song rows, Library, Search or Player. */
    fun isAllowedInCollection(item: HomeItem.Playlist): Boolean =
        isApproved(item.imageUrl) || KosherWhitelistManager.isHomeItemWhitelisted(item)

    @Synchronized
    internal fun replace(array: JSONArray?) {
        val parsed = linkedMapOf<String, Decision>()
        if (array != null) for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("scope") != "home") continue
            val url = imageUrl(entry.optString("url")) ?: continue
            val id = entry.optString("id")
            if (id != key(url)) continue
            val status = entry.optString("status")
            // Duplicate or unrecognized records fail closed, regardless of their order.
            val safeStatus = if (parsed.containsKey(url) || status != "approved") "blocked" else status
            parsed[url] = Decision(id, url, safeStatus, entry.optString("updated_at"))
        }
        if (entries != parsed) {
            entries = parsed.toMap()
            revision.value += 1
        }
    }

    internal fun toJson(): JSONArray = JSONArray().apply {
        entries.forEach { (url, decision) -> put(JSONObject().apply {
            put("id", decision.id); put("url", url); put("scope", "home"); put("status", decision.status)
            put("updated_at", decision.updatedAt)
        }) }
    }
}
