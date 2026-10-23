package com.music.spotui.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class UpdatePhase { IDLE, DOWNLOADING, WAITING, VERIFYING, READY, ERROR }
internal data class UpdateDownloadState(
    val info: UpdateChecker.UpdateInfo? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val downloaded: Long = 0,
    val total: Long = -1,
    val message: String = "",
) {
    val progress: Float? get() = if (total > 0) (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    val active get() = phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.WAITING, UpdatePhase.VERIFYING)
}
internal data class PendingUpdate(val info: UpdateChecker.UpdateInfo, val downloadId: Long)
internal enum class DownloadStatus { RUNNING, WAITING, COMPLETE, FAILED, MISSING }
internal data class UpdateDownloadSnapshot(val status: DownloadStatus, val downloaded: Long = 0, val total: Long = -1, val message: String = "")
internal interface UpdateDownloadStore {
    fun read(): PendingUpdate?
    fun write(pending: PendingUpdate?)
}
internal interface UpdateDownloadBackend {
    fun enqueue(info: UpdateChecker.UpdateInfo): Long
    fun snapshot(pending: PendingUpdate): UpdateDownloadSnapshot
    /** Returns a user-facing error; null means the exact APK is verified. */
    fun verify(pending: PendingUpdate): String?
    fun remove(pending: PendingUpdate)
}

/** Serializes cancellation/retry/verification; the system download itself survives process death. */
internal class UpdateDownloadSession(
    private val backend: UpdateDownloadBackend,
    private val store: UpdateDownloadStore,
    private val installedCode: Long,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(UpdateDownloadState())
    val state = mutableState.asStateFlow()
    private var verified: PendingUpdate? = null

    suspend fun start(info: UpdateChecker.UpdateInfo) = lock.withLock {
        val error = UpdateManifest.requestError(info, installedCode)
        if (error != null) { mutableState.value = UpdateDownloadState(info, UpdatePhase.ERROR, message = error); return@withLock }
        try {
            val old = store.read()
            if (old?.info == info && backend.snapshot(old).status in setOf(DownloadStatus.RUNNING, DownloadStatus.WAITING, DownloadStatus.COMPLETE)) {
                refreshLocked(); return@withLock
            }
            old?.let(backend::remove)
            store.write(null)
            verified = null
            val pending = PendingUpdate(info, backend.enqueue(info))
            try { store.write(pending) } catch (failure: Exception) { backend.remove(pending); throw failure }
            mutableState.value = UpdateDownloadState(info, UpdatePhase.WAITING, message = "מתחילים להוריד…")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = UpdateDownloadState(info, UpdatePhase.ERROR, message = "לא ניתן להתחיל את ההורדה. בדקו חיבור ומקום פנוי ונסו שוב.") }
    }

    suspend fun refresh() = lock.withLock { refreshLocked() }

    private fun refreshLocked() {
        val pending = store.read() ?: return
        try {
            if (pending.info.versionCode <= installedCode) {
                backend.remove(pending); store.write(null); verified = null
                mutableState.value = UpdateDownloadState(); return
            }
            val snapshot = backend.snapshot(pending)
            mutableState.value = when (snapshot.status) {
                DownloadStatus.RUNNING -> UpdateDownloadState(pending.info, UpdatePhase.DOWNLOADING, snapshot.downloaded, snapshot.total)
                DownloadStatus.WAITING -> UpdateDownloadState(pending.info, UpdatePhase.WAITING, snapshot.downloaded, snapshot.total, snapshot.message)
                DownloadStatus.COMPLETE -> {
                    if (verified != pending) {
                        mutableState.value = UpdateDownloadState(pending.info, UpdatePhase.VERIFYING, snapshot.downloaded, snapshot.total, "בודקים את קובץ העדכון…")
                        val error = backend.verify(pending)
                        if (error != null) {
                            backend.remove(pending); store.write(null)
                            mutableState.value = UpdateDownloadState(pending.info, UpdatePhase.ERROR, message = error)
                            return
                        }
                        verified = pending
                    }
                    UpdateDownloadState(pending.info, UpdatePhase.READY, snapshot.downloaded, snapshot.total, "העדכון מוכן להתקנה")
                }
                DownloadStatus.FAILED, DownloadStatus.MISSING -> UpdateDownloadState(pending.info, UpdatePhase.ERROR,
                    message = snapshot.message.ifBlank { "ההורדה לא הושלמה. אפשר לנסות שוב." })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = UpdateDownloadState(pending.info, UpdatePhase.ERROR, message = "לא ניתן לבדוק את ההורדה כרגע. נסו שוב.") }
    }

    suspend fun cancel() = lock.withLock {
        store.read()?.let(backend::remove)
        store.write(null); verified = null; mutableState.value = UpdateDownloadState()
    }

    /** Always revalidate just before handing a file to the installer, even after permission settings. */
    suspend fun verifiedForInstall(): PendingUpdate? = lock.withLock {
        val pending = store.read() ?: return@withLock null
        try {
            if (pending.info.versionCode <= installedCode || backend.snapshot(pending).status != DownloadStatus.COMPLETE) {
                refreshLocked()
                return@withLock null
            }
            val error = backend.verify(pending)
            if (error != null) {
                backend.remove(pending); store.write(null); verified = null
                mutableState.value = UpdateDownloadState(pending.info, UpdatePhase.ERROR, message = error)
                return@withLock null
            }
            pending
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            verified = null
            mutableState.value = UpdateDownloadState(pending.info, UpdatePhase.ERROR, message = "לא ניתן לבדוק את קובץ העדכון. נסו שוב.")
            null
        }
    }
}
