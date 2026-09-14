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

        sendTelegramMessage(context, text, replyMarkup, "הבקשה נשלחה בהצלחה לבדיקה!", onComplete)
    }

    /**
     * Sends a track approval request to the Telegram channel.
     */
    fun sendTrackApprovalRequest(
        context: Context,
        trackTitle: String,
        artistName: String,
        trackId: String,
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

        sendTelegramMessage(context, text, replyMarkup, "הבקשה נשלחה בהצלחה לבדיקה!", onComplete)
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

        sendTelegramMessage(context, text, replyMarkup, "הדיווח נשלח בהצלחה לבדיקת המנהל!", onComplete)
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

        sendTelegramMessage(context, text, replyMarkup, "הדיווח נשלח בהצלחה לבדיקת המנהל!", onComplete)
    }

    private fun sendTelegramMessage(
        context: Context,
        markdownText: String,
        replyMarkup: JSONObject? = null,
        successToast: String = "הבקשה נשלחה בהצלחה לבדיקה!",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("User-Agent", "SpotifyKosherApp/1.0")

                val payload = JSONObject().apply {
                    put("chat_id", CHANNEL_ID)
                    put("text", markdownText)
                    put("parse_mode", "Markdown")
                    if (replyMarkup != null) {
                        put("reply_markup", replyMarkup)
                    }
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                success = responseCode in 200..299
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, successToast, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "שגיאה בשליחה, נסה שנית.", Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke(success)
            }
        }
    }
}
