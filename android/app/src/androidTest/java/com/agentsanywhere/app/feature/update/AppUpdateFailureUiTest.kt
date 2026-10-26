package com.agentsanywhere.app.feature.update

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.R
import com.agentsanywhere.app.ui.designsystem.AAAppearanceMode
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import com.agentsanywhere.app.ui.screens.update.AppUpdateFailureDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateFailureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun persistedConflictDisplaysAfterRestartWithoutReleaseAndCanBeDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = AppUpdateStorage(context)
        storage.saveFailure(UpdateInstallFailure.Conflict)
        val vm = AppUpdateViewModel(context.applicationContext as Application)
        try {
            compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
                AppUpdateFailureDialog(vm.state.installFailure, vm::dismissInstallFailure)
            } }
            compose.runOnIdle { vm.consumeInstallResult(); vm.syncSession(false) }
            assertNull(vm.state.release)
            compose.onNodeWithText(context.getString(R.string.update_install_conflict)).assertIsDisplayed()
            android.os.SystemClock.sleep(600) // Wait for the system dialog window to finish its entrance animation.
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val dir = File(context.getExternalFilesDir(null), "update-qa").apply { mkdirs() }
            File(dir, "install-conflict.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
            compose.runOnIdle { assertNull(vm.state.installFailure); assertNull(storage.takeFailure()) }
        } finally { storage.clearFailure() }
    }
}
