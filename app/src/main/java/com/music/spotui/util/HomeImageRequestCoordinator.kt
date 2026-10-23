package com.music.spotui.util

import kotlinx.coroutines.CancellationException

internal enum class HomeRequestResult { APPROVED, PENDING, IN_FLIGHT, SYNC_FAILED, SENT, FAILED }

/** One exact-image request in flight across Home taps and the manual cover menu. */
internal class HomeImageRequestCoordinator {
    private val active = mutableSetOf<String>()
    suspend fun request(key: String, allowed: () -> Boolean, pending: () -> Boolean,
        sync: suspend () -> Boolean, send: suspend () -> Boolean): HomeRequestResult {
        if (!synchronized(active) { active.add(key) }) return HomeRequestResult.IN_FLIGHT
        try {
            if (allowed()) return HomeRequestResult.APPROVED
            if (pending()) return HomeRequestResult.PENDING
            val synced = sync()
            // Includes both exact Home grants and the item's existing global allowance.
            if (allowed()) return HomeRequestResult.APPROVED
            if (!synced) return HomeRequestResult.SYNC_FAILED
            if (pending()) return HomeRequestResult.PENDING
            return if (send()) HomeRequestResult.SENT else HomeRequestResult.FAILED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return HomeRequestResult.FAILED }
        finally { synchronized(active) { active.remove(key) } }
    }
}
