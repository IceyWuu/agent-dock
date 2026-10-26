package com.agentsanywhere.app.feature.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTerminalForegroundServiceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun rapidEnterExitHonorsForegroundDeadlineAndStopsTheService() {
        activity.scenario.onActivity { screen ->
            // Reproduce navigation/configuration disposal before service creation.
            repeat(20) {
                RemoteTerminalForegroundService.start(screen)
                RemoteTerminalForegroundService.stop(screen)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // The old implementation can be killed asynchronously by Android's FGS deadline.
        SystemClock.sleep(6_000)
        activity.scenario.onActivity { screen ->
            val manager = screen.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val running = manager.getRunningServices(100)
            assertFalse(running.any { it.service.className == RemoteTerminalForegroundService::class.java.name })
        }
    }
}
