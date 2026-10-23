package com.music.spotui.ui.screens

import android.content.Context
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.HomeImageApprovalRequest
import com.music.spotui.util.TelegramNotifier

/** Called only by Home cover click handlers; never by image loading or navigation elsewhere. */
internal fun requestHomeCoverOnTap(context: Context, item: HomeItem) =
    requestHomeCoverOnTap(context, item.name, item.imageUrl) { HomeArtworkPermissions.isAllowedOnHome(item) }

internal fun requestHomeCoverOnTap(context: Context, title: String, url: String, allowed: () -> Boolean) {
    val request = HomeImageApprovalRequest.create(title, url) ?: return
    if (allowed()) return
    TelegramNotifier.sendHomeImageApprovalRequest(context.applicationContext, request,
        automatic = true, isAllowed = allowed, onComplete = {})
}
