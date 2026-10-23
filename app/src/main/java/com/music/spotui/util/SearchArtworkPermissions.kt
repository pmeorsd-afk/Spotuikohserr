package com.music.spotui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Separate from the global URL whitelist. Never register these URLs with allowImageUrl. */
object SearchArtworkPermissions {
    internal data class Decision(val id: String, val url: String, val status: String, val updatedAt: String) {
        val token: String get() = "$status:$updatedAt"
    }
    private var entries by mutableStateOf<Map<String, Decision>>(emptyMap())

    internal fun decision(imageKey: String): Decision? = entries.values.singleOrNull { it.id == imageKey }

    // Reuse only the pure exact-URL validation/digest, never Home's decisions.
    fun imageUrl(raw: String): String? = HomeArtworkPermissions.imageUrl(raw)
    fun key(url: String): String = HomeArtworkPermissions.key(url)

    fun isApproved(url: String): Boolean = imageUrl(url)?.let { entries[it]?.status == "approved" } == true

    fun isAllowedInSearchCategory(url: String): Boolean =
        isApproved(url) || KosherWhitelistManager.isImageAllowed(url)

    internal fun replace(array: JSONArray?) {
        val parsed = linkedMapOf<String, Decision>()
        if (array != null) for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("scope") != "search") continue
            val url = imageUrl(entry.optString("url")) ?: continue
            val id = entry.optString("id")
            if (id != key(url)) continue
            val status = entry.optString("status")
            // Duplicate or unrecognized records fail closed, regardless of their order.
            val safeStatus = if (parsed.containsKey(url) || status != "approved") "blocked" else status
            parsed[url] = Decision(id, url, safeStatus, entry.optString("updated_at"))
        }
        entries = parsed.toMap()
    }

    internal fun toJson(): JSONArray = JSONArray().apply {
        entries.forEach { (url, decision) -> put(JSONObject().apply {
            put("id", decision.id); put("url", url); put("scope", "search"); put("status", decision.status)
            put("updated_at", decision.updatedAt)
        }) }
    }
}
