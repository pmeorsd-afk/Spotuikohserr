package com.music.spotui.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.music.spotui.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class AndroidUpdateDownloads(context: Context) : UpdateDownloadBackend {
    private val app = context.applicationContext
    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val session = UpdateDownloadSession(this, object : UpdateDownloadStore {
        private val prefs = app.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
        override fun read(): PendingUpdate? = runCatching {
            val json = JSONObject(prefs.getString("pending", null) ?: return null)
            val info = UpdateManifest.parse(json.getJSONObject("release").toString())
            val id = json.getLong("downloadId")
            if (id <= 0 || !UpdateManifest.httpsUrl(info.apkUrl)) return null
            PendingUpdate(info, id)
        }.getOrNull()
        override fun write(pending: PendingUpdate?) {
            val edit = prefs.edit()
            if (pending == null) edit.remove("pending") else edit.putString("pending", JSONObject().apply {
                put("release", UpdateManifest.encode(pending.info)); put("downloadId", pending.downloadId)
            }.toString())
            check(edit.commit()) { "Could not persist update download" }
        }
    }, BuildConfig.VERSION_CODE.toLong())

    private fun file(info: UpdateChecker.UpdateInfo): File {
        val root = File(checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates").canonicalFile
        check(root.isDirectory || root.mkdirs())
        val key = digest(info.apkUrl.toByteArray()).take(24)
        return File(root, "spotui-${info.versionCode}-$key.apk").canonicalFile.also { check(it.parentFile == root) }
    }

    override fun enqueue(info: UpdateChecker.UpdateInfo): Long {
        val destination = file(info)
        if (destination.exists()) check(destination.delete())
        return manager.enqueue(DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Spotify — עדכון ${info.versionName}")
            .setDescription("מורידים את העדכון לאפליקציה")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/${destination.name}")
            .addRequestHeader("User-Agent", "SpotUI-Update/${BuildConfig.VERSION_NAME}")
        ).also { check(it > 0) }
    }

    override fun snapshot(pending: PendingUpdate): UpdateDownloadSnapshot {
        manager.query(DownloadManager.Query().setFilterById(pending.downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return UpdateDownloadSnapshot(DownloadStatus.MISSING)
            fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
            fun int(name: String) = cursor.getInt(cursor.getColumnIndexOrThrow(name))
            // A restored ID must still point at this exact request and our dedicated file.
            val source = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
            val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val expected = Uri.fromFile(file(pending.info)).toString()
            // Some Android versions leave local_uri null until the request starts.
            if (source != pending.info.apkUrl || (local != null && local != expected)) return UpdateDownloadSnapshot(DownloadStatus.MISSING)
            val status = int(DownloadManager.COLUMN_STATUS)
            val reason = int(DownloadManager.COLUMN_REASON)
            val phase = when (status) {
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> DownloadStatus.WAITING
                DownloadManager.STATUS_SUCCESSFUL -> if (local == expected && file(pending.info).isFile) DownloadStatus.COMPLETE else DownloadStatus.MISSING
                else -> DownloadStatus.FAILED
            }
            val message = when {
                status == DownloadManager.STATUS_PAUSED -> "ממתינים לחיבור כדי להמשיך בהורדה…"
                status == DownloadManager.STATUS_PENDING -> "מתחילים להוריד…"
                reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "אין מספיק מקום פנוי. פנו מקום ונסו שוב."
                phase == DownloadStatus.FAILED -> "ההורדה נכשלה. בדקו את החיבור ונסו שוב."
                else -> ""
            }
            return UpdateDownloadSnapshot(phase, long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0), long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), message)
        }
        return UpdateDownloadSnapshot(DownloadStatus.MISSING)
    }

    @Suppress("DEPRECATION")
    override fun verify(pending: PendingUpdate): String? {
        val info = pending.info
        UpdateManifest.requestError(info, BuildConfig.VERSION_CODE.toLong())?.let { return it }
        val apk = file(info)
        if (!apk.isFile || apk.length() <= 0) return "קובץ העדכון חסר. נסו להוריד שוב."
        val snapshot = snapshot(pending)
        if (snapshot.status != DownloadStatus.COMPLETE || (snapshot.total > 0 && apk.length() != snapshot.total)) return "קובץ העדכון לא הורד בשלמותו. נסו שוב."
        if (info.apkSha256.isNotBlank()) {
            val hash = MessageDigest.getInstance("SHA-256")
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            if (!hex(hash.digest()).equals(info.apkSha256, true)) return "בדיקת קובץ העדכון נכשלה. נסו להוריד שוב."
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = app.packageManager
        val installed = pm.getPackageInfo(app.packageName, flags)
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        return UpdatePackagePolicy.error(identity(installed), archive?.let(::identity), info.versionCode.toLong(), Build.VERSION.SDK_INT)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): UpdatePackageIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return UpdatePackageIdentity(info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            certificates.orEmpty().map { digest(it.toByteArray()) }.toSet(), info.applicationInfo?.minSdkVersion ?: 0)
    }

    override fun remove(pending: PendingUpdate) {
        // Never cancel a different system download if preferences were restored/corrupted.
        manager.query(DownloadManager.Query().setFilterById(pending.downloadId))?.use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == pending.info.apkUrl) {
                val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                if (local == null || local == Uri.fromFile(file(pending.info)).toString()) manager.remove(pending.downloadId)
            }
        }
        file(pending.info).takeIf { it.isFile }?.delete()
    }

    fun installIntent(pending: PendingUpdate): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file(pending.info))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
