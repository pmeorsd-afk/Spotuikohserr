package com.music.spotui.data.update

import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Additive to version.json: old clients still open downloadUrl. */
internal object UpdateManifest {
    fun parse(text: String, isAdmin: Boolean = false): UpdateChecker.UpdateInfo {
        val envelope = JSONObject(text)
        val json = if (envelope.optString("encoding") == "base64" && envelope.has("content")) {
            JSONObject(String(Base64.getMimeDecoder().decode(envelope.getString("content")), Charsets.UTF_8))
        } else envelope
        return UpdateChecker.UpdateInfo(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            title = json.optString("title", "עדכון חדש מחכה לכם"),
            message = json.optString("message", "גרסה חדשה זמינה להורדה."),
            downloadUrl = json.optString("downloadUrl", "https://github.com/pmeorsd-afk/Spotuikohserr/releases/latest"),
            forceUpdate = json.optBoolean("forceUpdate", false),
            updateButtonText = json.optString("updateButtonText", "עדכן עכשיו"),
            cancelButtonText = json.optString("cancelButtonText", "מאוחר יותר"),
            // Admin has a distinct package/signature identity: never download the User APK for it.
            apkUrl = json.optString(if (isAdmin) "adminApkUrl" else "apkUrl", "").trim(),
            apkSha256 = json.optString(if (isAdmin) "adminApkSha256" else "apkSha256", "").trim(),
        )
    }

    fun encode(info: UpdateChecker.UpdateInfo) = JSONObject().apply {
        put("versionCode", info.versionCode); put("versionName", info.versionName)
        put("title", info.title); put("message", info.message); put("downloadUrl", info.downloadUrl)
        put("forceUpdate", info.forceUpdate); put("updateButtonText", info.updateButtonText)
        put("cancelButtonText", info.cancelButtonText); put("apkUrl", info.apkUrl); put("apkSha256", info.apkSha256)
    }

    fun requestError(info: UpdateChecker.UpdateInfo, installedCode: Long): String? = when {
        info.versionCode <= installedCode -> "מספר גרסת העדכון אינו חדש יותר מהגרסה המותקנת."
        !httpsUrl(info.apkUrl) -> "קישור ההורדה של העדכון אינו תקין."
        info.apkSha256.isNotBlank() && !info.apkSha256.matches(Regex("[a-fA-F0-9]{64}")) -> "פרטי קובץ העדכון אינם תקינים."
        else -> null
    }

    fun httpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)
}
