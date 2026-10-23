package com.music.spotui.ui.notification

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Glide
import com.music.spotui.playback.AutoMediaCatalog
import com.music.spotui.playback.AutoBrowseCatalog
import com.music.spotui.util.KosherWhitelistManager
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/** Read-only car artwork. No caller-supplied URL/path is ever fetched or opened. */
class AutoArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        if (mode != "r" || uri.authority != "${ctx.packageName}.auto.artwork" ||
            uri.pathSegments.size != 1 || uri.fragment != null ||
            (uri.query != null && (uri.queryParameterNames != setOf("v") || uri.getQueryParameters("v").size != 1))) throw FileNotFoundException()
        val id = uri.pathSegments.single()
        AutoLocalArtwork.render(ctx, id)?.let { bitmap ->
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            return pngPipe(uri, bytes)
        }
        val artwork = if (id.startsWith("auto-folder/")) {
            val prefs = ctx.getSharedPreferences("AutoBrowseCatalog", Context.MODE_PRIVATE)
            val entry = AutoBrowseCatalog({ prefs.getString("entries", null) }, {}).find(id)
                ?: throw FileNotFoundException()
            if (!AutoArtworkPolicy.allowed(entry, ctx)) throw FileNotFoundException()
            entry.artwork
        } else {
            val nowPlaying = id.startsWith("now-playing/")
            val prefs = ctx.getSharedPreferences(if (nowPlaying) "AutoNowPlayingArtwork" else "AutoMediaCatalog", Context.MODE_PRIVATE)
            val lookupId = if (nowPlaying) id.replaceFirst("now-playing/", "auto/") else id
            val song = AutoMediaCatalog({ prefs.getString("entries", null) }, {}).find(lookupId)?.song
                ?: throw FileNotFoundException()
            val allowed = if (nowPlaying) KosherWhitelistManager.isSongWhitelisted(song) else AutoArtworkPolicy.allowedBrowseSong(song)
            if (!allowed) throw FileNotFoundException()
            song.coverUri
        }
        if (Uri.parse(artwork).scheme != "https" || !AutoArtworkPolicy.matchesVersion(uri.getQueryParameter("v"), artwork)) throw FileNotFoundException()
        val target = Glide.with(ctx).asBitmap().load(artwork).submit(512, 512)
        val bytes = try {
            val bitmap = target.get(8, TimeUnit.SECONDS)
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        } catch (e: Exception) {
            throw FileNotFoundException("Car artwork unavailable").apply { initCause(e) }
        } finally { Glide.with(ctx).clear(target) }
        return pngPipe(uri, bytes)
    }

    private fun pngPipe(uri: Uri, bytes: ByteArray): ParcelFileDescriptor =
        openPipeHelper(uri, "image/png", null, bytes) { output, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) }
        }
}
