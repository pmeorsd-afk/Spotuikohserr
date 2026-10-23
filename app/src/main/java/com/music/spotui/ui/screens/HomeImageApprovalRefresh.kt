package com.music.spotui.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.music.spotui.util.HomeImageApprovalRequests
import com.music.spotui.util.awaitArtworkPermissionSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Pick up decisions from other devices while visible; fast pending checks remain bounded. */
@Composable
internal fun HomeImageApprovalRefresh(owner: LifecycleOwner) {
    val context = LocalContext.current.applicationContext
    val revision by HomeImageApprovalRequests.revision
    LaunchedEffect(owner, revision) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var fastChecks = 0
            while (isActive) {
                awaitArtworkPermissionSync(context)
                val pending = HomeImageApprovalRequests.hasPending(context)
                delay(if (pending && fastChecks++ < 8) 15_000 else 60_000)
            }
        }
    }
}
