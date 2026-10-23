package com.music.spotui.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.music.spotui.util.SearchImageApprovalRequests
import com.music.spotui.util.KosherWhitelistManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Bounded checks after a request/return from Telegram; no polling in the background. */
@Composable
internal fun SearchImageApprovalRefresh(owner: LifecycleOwner) {
    val context = LocalContext.current.applicationContext
    val revision by SearchImageApprovalRequests.revision
    LaunchedEffect(owner, revision) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (SearchImageApprovalRequests.hasPending(context)) repeat(8) {
                suspendCancellableCoroutine { continuation ->
                    KosherWhitelistManager.syncWithRemote(context) { success ->
                        if (continuation.isActive) continuation.resume(success)
                    }
                }
                if (!SearchImageApprovalRequests.hasPending(context)) return@repeatOnLifecycle
                delay(15_000)
            }
        }
    }
}
