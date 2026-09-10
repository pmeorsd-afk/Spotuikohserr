package com.music.spotui.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class KosherWhitelistProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.music.spotui.provider.whitelist"
        const val ADMIN_AUTHORITY = "com.music.spotui.admin.provider.whitelist"
        val CONTENT_URI_ADMIN: Uri = Uri.parse("content://$ADMIN_AUTHORITY/whitelist")
        val CONTENT_URI_USER: Uri = Uri.parse("content://$AUTHORITY/whitelist")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("json"))
        val json = KosherWhitelistManager.exportWhitelistJson()
        cursor.addRow(arrayOf(json))
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
