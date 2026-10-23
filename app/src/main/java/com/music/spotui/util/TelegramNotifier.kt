package com.music.spotui.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelegramNotifier {
    private const val BOT_TOKEN = "8800365444:AAH2W5JBJhrytzmthZMI1TlmzDTpNWnTlo4"
    private const val CHANNEL_ID = "-1004491387106" // @spotifty_kosher

    /**
     * Sends an artist approval request to the Telegram channel.
     */
    fun sendArtistApprovalRequest(
        context: Context,
        artistName: String,
        artistId: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanId = artistId.trim()
        val spotifyUrl = if (cleanId.isNotBlank()) "https://open.spotify.com/artist/$cleanId" else ""

        val text = buildString {
            append("📢 *בקשת היתר תמונות לאמן*\n\n")
            append("🎤 *אמן:* $artistName\n")
            if (cleanId.isNotBlank()) {
                append("🆔 *מזהה ספוטיפיי:* `$cleanId`\n")
            }
            if (spotifyUrl.isNotBlank()) {
                append("🔗 [פתח פרופיל בספוטיפיי]($spotifyUrl)\n")
            }
            append("\n📱 *נשלח מתוך אפליקציית ספוטיפיי כשר*")
        }

        val replyMarkup = JSONObject().apply {
            val keyboard = JSONArray().apply {
                val row1 = JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "✅ אשר והוסף לרשימה")
                        put("callback_data", if (cleanId.isNotBlank()) "appr:art:$cleanId" else "appr:art")
                    })
                }
                put(row1)

                if (spotifyUrl.isNotBlank()) {
                    val row2 = JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🎧 פתח בספוטיפיי")
                            put("url", spotifyUrl)
                        })
                    }
                    put(row2)
                }
            }
            put("inline_keyboard", keyboard)
        }

        sendTelegramMessage(
            context = context,
            markdownText = text,
            replyMarkup = replyMarkup,
            successToast = "הבקשה נשלחה בהצלחה לבדיקה!",
            silent = false,
            onComplete = onComplete
        )
    }

    /**
     * Sends a track approval request to the Telegram channel.
     */
    fun sendTrackApprovalRequest(
        context: Context,
        trackTitle: String,
        artistName: String,
        trackId: String,
        coverUrl: String = "",
        silent: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanId = trackId.trim()
        val spotifyUrl = if (cleanId.isNotBlank()) "https://open.spotify.com/track/$cleanId" else ""

        val text = buildString {
            append("🎵 *בקשת היתר תמונות לשיר*\n\n")
            append("🎶 *שיר:* $trackTitle\n")
            append("🎤 *אמן:* $artistName\n")
            if (cleanId.isNotBlank()) {
                append("🆔 *מזהה ספוטיפיי:* `$cleanId`\n")
            }
            if (spotifyUrl.isNotBlank()) {
                append("🔗 [פתח שיר בספוטיפיי]($spotifyUrl)\n")
            }
            if (coverUrl.isNotBlank() && coverUrl.startsWith("http")) {
                append("🖼️ [צפה בתמונת עטיפה]($coverUrl)\n")
            }
            if (silent) {
                append("\n⚡ *נשלח אוטומטית בהשמעת השיר*")
            }
            append("\n📱 *נשלח מתוך אפליקציית ספוטיפיי כשר*")
        }

        val replyMarkup = JSONObject().apply {
            val keyboard = JSONArray().apply {
                val row1 = JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "✅ אשר והוסף לרשימה")
                        put("callback_data", if (cleanId.isNotBlank()) "appr:trk:$cleanId" else "appr:trk")
                    })
                }
                put(row1)

                val linkButtons = JSONArray()
                if (spotifyUrl.isNotBlank()) {
                    linkButtons.put(JSONObject().apply {
                        put("text", "🎧 ספוטיפיי")
                        put("url", spotifyUrl)
                    })
                }
                if (coverUrl.isNotBlank() && coverUrl.startsWith("http")) {
                    linkButtons.put(JSONObject().apply {
                        put("text", "🖼️ עטיפה")
                        put("url", coverUrl)
                    })
                }
                if (linkButtons.length() > 0) {
                    put(linkButtons)
                }
            }
            put("inline_keyboard", keyboard)
        }

        sendTelegramMessage(
            context = context,
            markdownText = text,
            replyMarkup = replyMarkup,
            successToast = "הבקשה נשלחה בהצלחה לבדיקה!",
            silent = silent,
            onComplete = onComplete
        )
    }

    /**
     * Sends an artist report request to the Telegram channel (when artist is already whitelisted).
     */
    fun sendArtistReportRequest(
        context: Context,
        artistName: String,
        artistId: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanId = artistId.trim()
        val spotifyUrl = if (cleanId.isNotBlank()) "https://open.spotify.com/artist/$cleanId" else ""

        val text = buildString {
            append("⚠️ *דיווח על היתר תמונות לאמן*\n\n")
            append("🎤 *אמן:* $artistName\n")
            if (cleanId.isNotBlank()) {
                append("🆔 *מזהה ספוטיפיי:* `$cleanId`\n")
            }
            if (spotifyUrl.isNotBlank()) {
                append("🔗 [פתח פרופיל בספוטיפיי]($spotifyUrl)\n")
            }
            append("\n📢 *האמן נמצא כעת ברשימת ההיתר, אך משתמש דיווח לבדיקה חוזרת.*")
            append("\n📱 *נשלח מתוך אפליקציית ספוטיפיי כשר*")
        }

        val replyMarkup = JSONObject().apply {
            val keyboard = JSONArray().apply {
                val row1 = JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "❌ הסר מרשימת ההיתר")
                        put("callback_data", if (cleanId.isNotBlank()) "rem:art:$cleanId" else "rem:art")
                    })
                }
                put(row1)

                if (spotifyUrl.isNotBlank()) {
                    val row2 = JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🎧 פתח בספוטיפיי")
                            put("url", spotifyUrl)
                        })
                    }
                    put(row2)
                }
            }
            put("inline_keyboard", keyboard)
        }

        sendTelegramMessage(
            context = context,
            markdownText = text,
            replyMarkup = replyMarkup,
            successToast = "הדיווח נשלח בהצלחה לבדיקת המנהל!",
            silent = false,
            onComplete = onComplete
        )
    }

    /**
     * Sends a track report request to the Telegram channel (when track is already whitelisted).
     */
    fun sendTrackReportRequest(
        context: Context,
        trackTitle: String,
        artistName: String,
        trackId: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanId = trackId.trim()
        val spotifyUrl = if (cleanId.isNotBlank()) "https://open.spotify.com/track/$cleanId" else ""

        val text = buildString {
            append("⚠️ *דיווח על היתר תמונות לשיר*\n\n")
            append("🎶 *שיר:* $trackTitle\n")
            append("🎤 *אמן:* $artistName\n")
            if (cleanId.isNotBlank()) {
                append("🆔 *מזהה ספוטיפיי:* `$cleanId`\n")
            }
            if (spotifyUrl.isNotBlank()) {
                append("🔗 [פתח שיר בספוטיפיי]($spotifyUrl)\n")
            }
            append("\n📢 *השיר/האמן נמצא כעת ברשימת ההיתר, אך משתמש דיווח לבדיקה חוזרת.*")
            append("\n📱 *נשלח מתוך אפליקציית ספוטיפיי כשר*")
        }

        val replyMarkup = JSONObject().apply {
            val keyboard = JSONArray().apply {
                val row1 = JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "❌ הסר מרשימת ההיתר")
                        put("callback_data", if (cleanId.isNotBlank()) "rem:trk:$cleanId" else "rem:trk")
                    })
                }
                put(row1)

                if (spotifyUrl.isNotBlank()) {
                    val row2 = JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🎧 פתח בספוטיפיי")
                            put("url", spotifyUrl)
                        })
                    }
                    put(row2)
                }
            }
            put("inline_keyboard", keyboard)
        }

        sendTelegramMessage(
            context = context,
            markdownText = text,
            replyMarkup = replyMarkup,
            successToast = "הדיווח נשלח בהצלחה לבדיקת המנהל!",
            silent = false,
            onComplete = onComplete
        )
    }

    private val homeRequestCoordinator = HomeImageRequestCoordinator()

    internal fun sendHomeImageApprovalRequest(context: Context, request: HomeImageApprovalRequest,
        automatic: Boolean = false, isAllowed: () -> Boolean = { HomeArtworkPermissions.isApproved(request.url) },
        onComplete: (Boolean) -> Unit) {
        val app = context.applicationContext
        // Application-owned: navigating away from Home must not cancel the tapped request.
        CoroutineScope(Dispatchers.Main).launch {
            val result = homeRequestCoordinator.request(HomeArtworkPermissions.key(request.url),
                allowed = isAllowed,
                pending = { HomeImageApprovalRequests.isPending(app, request.url) },
                sync = { awaitArtworkPermissionSync(app) },
                send = {
                    val decision = HomeImageApprovalRequests.decisionAtSend(request.url)
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                        sendTelegramMessage(app, request.text, request.keyboard(), silent = true, parseMode = null,
                            onComplete = { success ->
                                if (success) HomeImageApprovalRequests.recordSent(app, request.url, decision)
                                if (continuation.isActive) continuation.resumeWith(Result.success(success))
                            })
                    }
                })
            if (!automatic) {
                val message = when (result) {
                    HomeRequestResult.APPROVED -> "התמונה כבר מותרת. האישור עודכן במכשיר."
                    HomeRequestResult.PENDING, HomeRequestResult.IN_FLIGHT -> "הבקשה כבר נשלחה לבדיקה."
                    HomeRequestResult.SENT -> "הבקשה נשלחה. התמונה תופיע בבית לאחר אישור המנהל."
                    HomeRequestResult.SYNC_FAILED -> "לא ניתן לבדוק את האישור כרגע. נסו שוב כשיש חיבור."
                    HomeRequestResult.FAILED -> "שגיאה בשליחה, נסה שנית."
                }
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            }
            onComplete(result in setOf(HomeRequestResult.APPROVED, HomeRequestResult.PENDING, HomeRequestResult.SENT))
        }
    }

    internal fun sendSearchImageApprovalRequest(context: Context, request: SearchImageApprovalRequest, onComplete: (Boolean) -> Unit) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            if (!SearchArtworkLinkValidator().isAvailable(request.url)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "לא ניתן לפתוח את תמונת הקטגוריה כרגע. הבקשה לא נשלחה; נסו שוב מאוחר יותר.", Toast.LENGTH_LONG).show()
                    onComplete(false)
                }
                return@launch
            }
            val decisionAtSend = SearchImageApprovalRequests.decisionAtSend(request.url)
            sendTelegramMessage(
                context = app,
                markdownText = request.text,
                replyMarkup = request.keyboard(),
                successToast = "הבקשה נשלחה. התמונה תופיע בקטגוריות החיפוש לאחר אישור המנהל.",
                parseMode = null,
                onComplete = { success ->
                    if (success) SearchImageApprovalRequests.recordSent(app, request.url, decisionAtSend)
                    onComplete(success)
                },
            )
        }
    }

    private fun sendTelegramMessage(
        context: Context,
        markdownText: String,
        replyMarkup: JSONObject? = null,
        successToast: String = "הבקשה נשלחה בהצלחה לבדיקה!",
        silent: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
        parseMode: String? = "Markdown"
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("User-Agent", "SpotifyKosherApp/1.0")

                val payload = JSONObject().apply {
                    put("chat_id", CHANNEL_ID)
                    put("text", markdownText)
                    if (parseMode != null) put("parse_mode", parseMode)
                    if (replyMarkup != null) {
                        put("reply_markup", replyMarkup)
                    }
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                success = responseCode in 200..299 && conn.inputStream.bufferedReader().use {
                    JSONObject(it.readText()).optBoolean("ok", false)
                }
            } catch (e: Exception) {
                android.util.Log.w("TelegramNotifier", "Telegram request failed")
                success = false
            } finally {
                connection?.disconnect()
            }

            withContext(Dispatchers.Main) {
                if (!silent) {
                    if (success) {
                        Toast.makeText(context, successToast, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "שגיאה בשליחה, נסה שנית.", Toast.LENGTH_SHORT).show()
                    }
                }
                onComplete?.invoke(success)
            }
        }
    }
}
