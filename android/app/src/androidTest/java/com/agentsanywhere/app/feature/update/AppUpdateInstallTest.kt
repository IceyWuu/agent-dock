package com.agentsanywhere.app.feature.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated emulator only. Fixture supplied explicitly; no server or real conversations touched. */
@RunWith(AndroidJUnit4::class)
class AppUpdateInstallTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun androidRejectsDifferentSignerWithoutRemovingInstalledApp() {
        val path = InstrumentationRegistry.getArguments().getString("alternateFixture")
        org.junit.Assume.assumeNotNull(path)
        val storage = AppUpdateStorage(context)
        storage.clearFailure()
        storage.directory.mkdirs()
        val beforeVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val source = File(requireNotNull(path)).copyTo(File(storage.directory, "wrong-signature.apk"), overwrite = true)
        AppUpdateInstaller.install(context, source)
        assertFalse(source.exists())
        val deadline = SystemClock.uptimeMillis() + 20000
        var failure: UpdateInstallFailure? = null
        while (SystemClock.uptimeMillis() < deadline && failure == null) {
            failure = storage.takeFailure()
            val button = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            if (button?.text?.toString()?.lowercase() in setOf("update", "install", "更新", "安装")) {
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(100)
        }
        assertEquals(UpdateInstallFailure.Conflict, failure)
        assertEquals(beforeVersion, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
    }

    @Test fun cleanupRemovesOnlyPrivateUpdateApksAndPersistsFailure() {
        val storage = AppUpdateStorage(context)
        storage.directory.mkdirs()
        val stale = File(storage.directory, "stale.apk").apply { writeText("old update") }
        val unrelated = File(context.cacheDir, "keep-attachment.apk").apply { writeText("attachment") }
        val metadata = File(storage.directory, "keep.txt").apply { writeText("keep") }
        try {
            storage.deleteDownload(unrelated)
            assertTrue(unrelated.exists())
            storage.clearOrphanedDownloads()
            assertFalse(stale.exists())
            assertTrue(metadata.exists())
            assertTrue(unrelated.exists())
            storage.saveFailure(UpdateInstallFailure.Storage)
            assertEquals(UpdateInstallFailure.Storage, AppUpdateStorage(context).takeFailure())
            assertNull(storage.takeFailure())
        } finally { unrelated.delete(); metadata.delete(); storage.clearFailure() }
    }

    @Test fun terminalFailuresAreRetainedForNextLaunch() {
        val expected = mapOf(
            PackageInstaller.STATUS_FAILURE_CONFLICT to UpdateInstallFailure.Conflict,
            PackageInstaller.STATUS_FAILURE_STORAGE to UpdateInstallFailure.Storage,
            PackageInstaller.STATUS_FAILURE_BLOCKED to UpdateInstallFailure.Blocked,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to UpdateInstallFailure.Incompatible,
            PackageInstaller.STATUS_FAILURE_ABORTED to UpdateInstallFailure.Cancelled,
        )
        expected.forEach { (status, failure) ->
            AppUpdateInstallReceiver().onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, status))
            assertEquals(failure, AppUpdateStorage(context).takeFailure())
        }
    }

    @Test fun failedStagingAbandonsSessionAndDeletesPrivateSource() {
        val storage = AppUpdateStorage(context)
        storage.directory.mkdirs()
        // A directory cannot be read as an APK, so staging must fail before commit.
        val invalid = File(storage.directory, "invalid.apk").apply { mkdir() }
        val before = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        try {
            assertTrue(runCatching { AppUpdateInstaller.install(context, invalid) }.isFailure)
            val after = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
            assertEquals(before, after)
            assertFalse(invalid.exists())
        } finally { invalid.delete() }
    }

    @Test fun stagedPackageSurvivesSourceDeletionAndCancellation() {
        val fixturePath = InstrumentationRegistry.getArguments().getString("installFixture")
        org.junit.Assume.assumeNotNull(fixturePath)
        val fixture = File(requireNotNull(fixturePath))
        assertTrue(fixture.isFile)
        val storage = AppUpdateStorage(context)
        storage.clearFailure()
        storage.directory.mkdirs()
        val apk = fixture.copyTo(File(storage.directory, "test-update.apk"), overwrite = true)
        val before = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        AppUpdateInstaller.install(context, apk)
        assertFalse("Source must be deleted once Android owns a copy", apk.exists())
        val cancel = waitForButton("android:id/button2")
        assertNotNull("Android must show a confirmation for the staged APK", cancel)
        assertTrue(cancel!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val deadline = SystemClock.uptimeMillis() + 15000
        var failure: UpdateInstallFailure? = null
        while (SystemClock.uptimeMillis() < deadline && failure == null) {
            failure = storage.takeFailure()
            SystemClock.sleep(100)
        }
        assertEquals(UpdateInstallFailure.Cancelled, failure)
        val remaining = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet() - before
        assertTrue("Cancelled session must release its staged copy", remaining.isEmpty())
        assertTrue("External fixture must never be deleted", fixture.isFile)
    }

    @Test fun rejectWrongSignatureAndNonNewerPackages() {
        val fixturePath = InstrumentationRegistry.getArguments().getString("installFixture")
        org.junit.Assume.assumeNotNull(fixturePath)
        val file = File(requireNotNull(fixturePath))
        val packageInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0))
        val release = com.agentsanywhere.app.api.AndroidAppRelease(
            requireNotNull(packageInfo.versionName), "https://example.com/update.apk",
            packageInfo.longVersionCode,
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
            file.length(),
        )
        val error = runCatching { verifyUpdatePackage(context, file, release) }.exceptionOrNull()
        assertTrue(error is UpdateInstallException)
        assertEquals(UpdateInstallFailure.Version, (error as UpdateInstallException).reason)
        val alternatePath = InstrumentationRegistry.getArguments().getString("alternateFixture") ?: return
        val alternate = File(alternatePath)
        val otherRelease = release.copy(
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(alternate.readBytes()).joinToString("") { "%02x".format(it) },
            sizeBytes = alternate.length(),
        )
        val wrongSignature = runCatching { verifyUpdatePackage(context, alternate, otherRelease) }.exceptionOrNull()
        assertTrue(wrongSignature is UpdateInstallException)
        assertEquals(UpdateInstallFailure.Signature, (wrongSignature as UpdateInstallException).reason)
    }

    @Test fun stageConfirmationForManualAcceptance() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("acceptInstall") == "true")
        val fixture = File(requireNotNull(InstrumentationRegistry.getArguments().getString("installFixture")))
        val storage = AppUpdateStorage(context)
        storage.directory.mkdirs()
        val source = fixture.copyTo(File(storage.directory, "accept-update.apk"), overwrite = true)
        AppUpdateInstaller.install(context, source)
        assertFalse(source.exists())
        assertNotNull(waitForButton("android:id/button1"))
        // Let the instrumentation process finish before the host accepts replacement of its app.
    }

    private fun waitForButton(id: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null) return node
            SystemClock.sleep(100)
        }
        return null
    }
}
