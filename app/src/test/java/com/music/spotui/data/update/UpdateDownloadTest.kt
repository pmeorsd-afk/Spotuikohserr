package com.music.spotui.data.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class UpdateDownloadTest {
    private fun release(url: String = "https://github.com/example/app/releases/download/v2.9/app.apk") = UpdateChecker.UpdateInfo(
        29, "2.9", "עדכון", "חדש", "https://github.com/example/app/releases/tag/v2.9", apkUrl = url)
    private class MemoryStore : UpdateDownloadStore {
        var pending: PendingUpdate? = null
        override fun read() = pending
        override fun write(pending: PendingUpdate?) { this.pending = pending }
    }
    private class Backend : UpdateDownloadBackend {
        var enqueues = 0; var removes = 0; var verifies = 0
        var snapshot = UpdateDownloadSnapshot(DownloadStatus.RUNNING, 20, 100)
        var verificationError: String? = null
        var verificationThrows = false
        var removalThrows = false
        override fun enqueue(info: UpdateChecker.UpdateInfo) = (++enqueues).toLong()
        override fun snapshot(pending: PendingUpdate) = snapshot
        override fun verify(pending: PendingUpdate): String? { verifies++; check(!verificationThrows); return verificationError }
        override fun remove(pending: PendingUpdate) { check(!removalThrows); removes++ }
    }

    @Test fun legacyManifestStillUsesBrowserLink() {
        val info = UpdateManifest.parse("""{"versionCode":29,"versionName":"2.9","downloadUrl":"https://example.com/release"}""")
        assertEquals("", info.apkUrl); assertEquals("https://example.com/release", info.downloadUrl)
    }
    @Test fun gitHubApiEnvelopeAndPublicRawGiveSameRelease() {
        val raw = UpdateManifest.encode(release()).toString()
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
        assertEquals(UpdateManifest.parse(raw), UpdateManifest.parse("""{"encoding":"base64","content":"$encoded"}"""))
    }
    @Test fun adminNeverUsesUserApk() {
        val json = UpdateManifest.encode(release()).put("adminApkUrl", "https://example.com/admin.apk")
        assertEquals("https://example.com/admin.apk", UpdateManifest.parse(json.toString(), true).apkUrl)
        json.remove("adminApkUrl")
        assertEquals("", UpdateManifest.parse(json.toString(), true).apkUrl)
    }
    @Test fun persistenceRetainsForceFlagAndExactDownloadIdentity() {
        val info = release().copy(forceUpdate = true, apkSha256 = "a".repeat(64))
        assertEquals(info, UpdateManifest.parse(UpdateManifest.encode(info).toString()))
    }
    @Test fun rejectsUnsafeOrMalformedUrlsAndChecksum() {
        for (url in listOf("http://example.com/app.apk", "file:///app.apk", "https://user:pass@example.com/app.apk", "https://example.com/a#b", "bad")) {
            assertNotNull(UpdateManifest.requestError(release(url), 28))
        }
        assertNotNull(UpdateManifest.requestError(release().copy(apkSha256 = "bad"), 28))
        assertNull(UpdateManifest.requestError(release(), 28))
    }
    @Test fun versionNameAloneDoesNotAllowSameOrOlderApkInstall() {
        assertNotNull(UpdateManifest.requestError(release().copy(versionCode = 28, versionName = "9.0"), 28))
    }
    @Test fun repeatedTapReusesDownload() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); session.start(release())
        assertEquals(1, b.enqueues); assertEquals(UpdatePhase.DOWNLOADING, session.state.value.phase)
    }
    @Test fun restoresSystemDownloadAfterProcessRecreation() = runBlocking {
        val b = Backend(); val store = MemoryStore()
        UpdateDownloadSession(b, store, 28).start(release())
        val recreated = UpdateDownloadSession(b, store, 28)
        recreated.refresh()
        assertEquals(1, b.enqueues); assertEquals(0.2f, recreated.state.value.progress!!, 0.001f)
        assertEquals(release(), recreated.state.value.info)
    }
    @Test fun unknownContentLengthHasNoFakePercentage() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.RUNNING, 500, -1); session.refresh()
        assertNull(session.state.value.progress)
        assertEquals(500L, session.state.value.downloaded)
    }
    @Test fun connectionPausePreservesBytesAndContinuesSameDownload() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.WAITING, 60, 100); session.refresh()
        assertEquals(UpdatePhase.WAITING, session.state.value.phase); assertEquals(60L, session.state.value.downloaded)
        b.snapshot = UpdateDownloadSnapshot(DownloadStatus.RUNNING, 80, 100); session.refresh()
        assertEquals(1, b.enqueues); assertEquals(80L, session.state.value.downloaded)
    }
    @Test fun failureThenRetryCreatesOneReplacement() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.FAILED); session.refresh()
        assertEquals(UpdatePhase.ERROR, session.state.value.phase)
        session.start(release()); assertEquals(2, b.enqueues); assertEquals(1, b.removes)
    }
    @Test fun cancellationClearsOwnershipAndLateCompletionCannotRestoreDialog() = runBlocking {
        val b = Backend(); val store = MemoryStore(); val session = UpdateDownloadSession(b, store, 28)
        session.start(release()); session.cancel()
        b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        assertEquals(UpdatePhase.IDLE, session.state.value.phase); assertNull(store.pending)
        assertNull(session.verifiedForInstall()); assertEquals(0, b.verifies)
    }
    @Test fun completeMustBeVerifiedBeforeReady() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE, 100, 100); session.refresh()
        assertEquals(1, b.verifies); assertEquals(UpdatePhase.READY, session.state.value.phase)
    }
    @Test fun invalidArchiveIsRemovedAndNeverReady() = runBlocking {
        val b = Backend(); val store = MemoryStore(); val session = UpdateDownloadSession(b, store, 28)
        session.start(release()); b.verificationError = "invalid"; b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        assertEquals(UpdatePhase.ERROR, session.state.value.phase); assertNull(store.pending)
        assertNull(session.verifiedForInstall()); assertEquals(1, b.removes)
    }
    @Test fun rechecksFileAfterReturningFromInstallPermissionSettings() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        b.verificationError = "changed file"
        assertNull(session.verifiedForInstall()); assertEquals(2, b.verifies)
        assertEquals(UpdatePhase.ERROR, session.state.value.phase)
    }
    @Test fun deletedFileAfterReadyOffersRetryInsteadOfAnUnresponsiveInstallButton() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        b.snapshot = UpdateDownloadSnapshot(DownloadStatus.MISSING)
        assertNull(session.verifiedForInstall()); assertEquals(UpdatePhase.ERROR, session.state.value.phase)
        session.start(release()); assertEquals(2, b.enqueues)
    }
    @Test fun unreadableReadyFileCannotBeHandedToInstaller() = runBlocking {
        val b = Backend(); val session = UpdateDownloadSession(b, MemoryStore(), 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        b.verificationThrows = true
        assertNull(session.verifiedForInstall()); assertEquals(UpdatePhase.ERROR, session.state.value.phase)
    }
    @Test fun oldDownloadCleanupFailureDoesNotCrashStartupOrAllowDowngrade() = runBlocking {
        val b = Backend(); val store = MemoryStore()
        UpdateDownloadSession(b, store, 28).start(release())
        b.removalThrows = true
        val updated = UpdateDownloadSession(b, store, 29); updated.refresh()
        assertEquals(UpdatePhase.ERROR, updated.state.value.phase)
        assertNull(updated.verifiedForInstall()); assertEquals(0, b.verifies)
    }
    @Test fun installedNewVersionCleansOldDownloadOnNextLaunch() = runBlocking {
        val b = Backend(); val store = MemoryStore()
        UpdateDownloadSession(b, store, 28).start(release())
        val updated = UpdateDownloadSession(b, store, 29); updated.refresh()
        assertNull(store.pending); assertEquals(UpdatePhase.IDLE, updated.state.value.phase); assertEquals(1, b.removes)
    }
    @Test fun changedReleaseCannotReusePreviousDownloadOrVerification() = runBlocking {
        val b = Backend(); val store = MemoryStore(); val session = UpdateDownloadSession(b, store, 28)
        session.start(release()); b.snapshot = UpdateDownloadSnapshot(DownloadStatus.COMPLETE); session.refresh()
        val next = release("https://example.com/new.apk").copy(versionCode = 30)
        session.start(next); session.refresh()
        assertEquals(2, b.enqueues); assertEquals(2, b.verifies); assertEquals(next, store.pending?.info)
    }
    @Test fun storageFailureDoesNotLeaveAnUntrackedDownload() = runBlocking {
        val b = Backend()
        val store = object : UpdateDownloadStore {
            override fun read(): PendingUpdate? = null
            override fun write(pending: PendingUpdate?) { if (pending != null) error("disk full") }
        }
        val session = UpdateDownloadSession(b, store, 28); session.start(release())
        assertEquals(1, b.removes); assertEquals(UpdatePhase.ERROR, session.state.value.phase)
    }

    private val installed = UpdatePackageIdentity("com.music.spotui", 28, setOf("original"), 26)
    private val archive = installed.copy(versionCode = 29)
    @Test fun exactPackageVersionAndSignerAccepted() { assertNull(UpdatePackagePolicy.error(installed, archive, 29, 26)) }
    @Test fun adminUserPackageMixupRejected() { assertNotNull(UpdatePackagePolicy.error(installed, archive.copy(packageName = "com.music.spotui.admin"), 29, 34)) }
    @Test fun wrongOrMissingSigningCertificateRejected() {
        for (signers in listOf(emptySet(), setOf("other"), setOf("original", "other"))) assertNotNull(UpdatePackagePolicy.error(installed, archive.copy(signers = signers), 29, 34))
    }
    @Test fun wrongVersionAndDowngradeRejected() {
        assertNotNull(UpdatePackagePolicy.error(installed, archive.copy(versionCode = 30), 29, 34))
        assertNotNull(UpdatePackagePolicy.error(installed, archive.copy(versionCode = 27), 27, 34))
    }
    @Test fun unsupportedAndroidAndHtmlInsteadOfApkRejected() {
        assertNotNull(UpdatePackagePolicy.error(installed, archive.copy(minSdk = 35), 29, 34))
        assertNotNull(UpdatePackagePolicy.error(installed, null, 29, 34))
    }
}
