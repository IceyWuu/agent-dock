package com.agentsanywhere.app.feature.update

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.AppUpdatesApi
import com.agentsanywhere.app.ui.designsystem.AAAppearanceMode
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import com.agentsanywhere.app.ui.screens.update.AppUpdatePromptDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live distribution test, on a lower-build isolated emulator. Never answers agent requests. */
@RunWith(AndroidJUnit4::class)
class AppUpdateServerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun serverReleasePromptsDownloadsAndValidatesSameSignedUpgrade() {
        val server = InstrumentationRegistry.getArguments().getString("updateServer") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val preferences = context.getSharedPreferences("agents_anywhere_auth", Context.MODE_PRIVATE)
        val previous = preferences.all
        val updatePreferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        val ignoredKey = "ignored:$server"
        val previousIgnored = updatePreferences.getString(ignoredKey, null)
        val viewModel = AppUpdateViewModel(application)
        try {
            preferences.edit().putString("server_url", server).putString("access_token", "isolated-update-probe").commit()
            updatePreferences.edit().remove(ignoredKey).commit()
            compose.setContent {
                AgentsAnywhereTheme(AAAppearanceMode.Light) {
                    AppUpdatePromptDialog(viewModel.state, onUpdate = viewModel::downloadUpdate, onIgnore = viewModel::ignoreVersion)
                }
            }
            compose.runOnIdle { viewModel.syncSession(true) }
            compose.waitUntil(30000) { viewModel.state.checked }
            assertFalse("The public update check must succeed", viewModel.state.checkFailed)
            assertTrue(viewModel.state.promptVisible)
            val release = requireNotNull(viewModel.state.release)
            assertEquals(InstrumentationRegistry.getArguments().getString("expectedVersion") ?: "2.1.2", release.versionName)
            assertEquals(InstrumentationRegistry.getArguments().getString("expectedCode")?.toLong() ?: 14L, release.versionCode)
            compose.onNodeWithText(context.getString(R.string.update_now)).assertIsDisplayed()
            capture("update-available")
            compose.onNodeWithText(context.getString(R.string.update_ignore_version)).performClick()
            compose.waitUntil(5000) { !viewModel.state.promptVisible }
            compose.runOnIdle { viewModel.checkForUpdate(showPrompt = true) }
            compose.waitUntil(30000) { !viewModel.state.checking }
            assertFalse("Ignored builds must not repeatedly prompt", viewModel.state.promptVisible)
            compose.runOnIdle { viewModel.checkForUpdate(showPrompt = true, forcePrompt = true) }
            compose.waitUntil(30000) { !viewModel.state.checking }
            compose.onNodeWithText(context.getString(R.string.update_now)).performClick()
            compose.waitUntil(240000) { viewModel.state.installFile != null || viewModel.state.downloadFailed }
            assertFalse("Download, checksum, metadata and signature must all pass", viewModel.state.downloadFailed)
            val file = requireNotNull(viewModel.state.installFile)
            verifyUpdatePackage(context, file, release)
            val directory = File(context.getExternalFilesDir(null), "update-qa").apply { mkdirs() }
            file.copyTo(File(directory, "verified-update.apk"), overwrite = true)
            File(directory, "result.txt").writeText("Public manifest, update prompt, ignore, manual recheck, real HTTPS APK download, size, SHA-256, package, version and signing identity verified.\n")
            capture("download-verified")
        } finally {
            viewModel.cancelDownload()
            val edit = preferences.edit().clear()
            previous.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
            } }
            edit.commit()
            if (previousIgnored == null) updatePreferences.edit().remove(ignoredKey).commit()
            else updatePreferences.edit().putString(ignoredKey, previousIgnored).commit()
        }
    }
    @Test fun verifiedUpgradeOpensSystemInstallConfirmation() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("stageInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "update-qa/verified-update.apk")
        assertTrue(file.isFile)
        compose.runOnUiThread { AppUpdateInstaller.install(context, file) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 20000
        var confirmationShown = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val buttons = root?.findAccessibilityNodeInfosByViewId("android:id/button1").orEmpty()
            if (buttons.any { it.text?.toString()?.lowercase() in setOf("update", "install", "更新", "安装") }) {
                confirmationShown = true
                break
            }
            SystemClock.sleep(100)
        }
        assertTrue("Android must request user confirmation before upgrading", confirmationShown)
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(context.getExternalFilesDir(null), "update-qa/system-install-confirmation.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "update-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
