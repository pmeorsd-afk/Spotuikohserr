package com.music.spotui.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        sendTelegramMessage(context, text, onComplete)
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

        sendTelegramMessage(context, text, onComplete)
    }

    private fun sendTelegramMessage(
        context: Context,
        markdownText: String,
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
                    Toast.makeText(context, "הבקשה נשלחה בהצלחה לבדיקה!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "שגיאה בשליחת הבקשה, נסה שנית.", Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke(success)
            }
        }
    }
}
