package com.music.spotui.util

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal suspend fun awaitArtworkPermissionSync(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
    KosherWhitelistManager.syncWithRemote(context.applicationContext) { success ->
        if (continuation.isActive) continuation.resume(success)
    }
}

internal enum class HomeImageRequestReadiness { APPROVED, READY, SYNC_FAILED }

/** Re-check shared decisions before a device with an old cache sends a duplicate request. */
internal suspend fun homeImageRequestReadiness(url: String, sync: suspend () -> Boolean): HomeImageRequestReadiness {
    val synced = sync()
    return when {
        HomeArtworkPermissions.isApproved(url) -> HomeImageRequestReadiness.APPROVED
        !synced -> HomeImageRequestReadiness.SYNC_FAILED
        else -> HomeImageRequestReadiness.READY
    }
}
