package com.music.spotui.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

internal data class SearchImageApprovalRequest(val title: String, val url: String) {
    val text: String get() = "🖼️ בקשת היתר לתמונה בקטגוריות מסך החיפוש בלבד\n\nקטגוריה: ${title.take(300)}\n" +
        "האישור חל על כתובת התמונה המדויקת בקטגוריות מסך החיפוש בלבד. הוא אינו מתיר שירים, אמנים או תמונות במסכים אחרים."
    fun keyboard(): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
        put(JSONArray().apply {
            put(JSONObject().put("text", "✅ אישור לקטגוריות בחיפוש").put("callback_data", "appr:search:${SearchArtworkPermissions.key(url)}"))
            put(JSONObject().put("text", "❌ דחייה").put("callback_data", "deny:search:${SearchArtworkPermissions.key(url)}"))
        })
        put(JSONArray().put(JSONObject().put("text", "🖼️ הצגת התמונה").put("url", url)))
    })

    companion object {
        fun create(title: String, url: String): SearchImageApprovalRequest? =
            SearchArtworkPermissions.imageUrl(url)?.let { SearchImageApprovalRequest(title, it) }
    }
}

/** Request state is not an image grant. A failed send remains immediately retryable. */
internal object SearchImageApprovalRequests {
    val revision = mutableIntStateOf(0)
    private const val PREF = "search_image_requests"
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L
    private const val DECISION_SUFFIX = ":decision"

    /** Capture before sending, so a fast administrator decision is not mistaken for an old one. */
    fun decisionAtSend(url: String): String = SearchArtworkPermissions.decision(SearchArtworkPermissions.key(url))?.token.orEmpty()

    fun isPending(context: Context, url: String): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return isPending(prefs, SearchArtworkPermissions.key(url), System.currentTimeMillis())
    }
    fun hasPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return prefs.all.any { (id, value) -> value is Long && isPending(prefs, id, now) }
    }

    private fun isPending(prefs: SharedPreferences, id: String, now: Long): Boolean {
        val sent = prefs.getLong(id, 0)
        if (sent <= 0 || now - sent !in 0 until RETRY_AFTER_MS) return false
        val decision = SearchArtworkPermissions.decision(id) ?: return true
        if (decision.status == "approved") return false
        // Missing baseline is an old app's pending record: a known rejection releases it too.
        // A new request after rejection keeps waiting until this exact image gets a new decision.
        return prefs.getString(id + DECISION_SUFFIX, null) == decision.token
    }

    fun recordSent(context: Context, url: String, decisionAtSend: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            prefs.all.forEach { (key, value) ->
                if (value is Long && now - value !in 0 until RETRY_AFTER_MS) {
                    remove(key)
                    remove(key + DECISION_SUFFIX)
                }
            }
            val id = SearchArtworkPermissions.key(url)
            putLong(id, now)
            putString(id + DECISION_SUFFIX, decisionAtSend)
        }.apply()
        revision.intValue++
    }
}
