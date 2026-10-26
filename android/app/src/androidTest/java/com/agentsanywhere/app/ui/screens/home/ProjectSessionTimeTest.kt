package com.agentsanywhere.app.ui.screens.home

import android.graphics.Bitmap
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.model.*
import com.agentsanywhere.app.ui.designsystem.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProjectSessionTimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun session(id: String, title: String, updated: String?, status: SessionStatus = SessionStatus.Idle) = AgentSession(
        id, "device", "project", "Laptop", title, "", null, "workspace", "codex", "Codex", status, "", "", "",
        false, false, false, 0, false, true, false, "", 0, sortAt = updated,
    )
    @Test fun todayAndOlderDatesAppearOnRight() = checkRows(false, 1f)
    @Test fun timesRemainReadableBesideLongTitlesAndApprovalAtLargeSize() = checkRows(true, 2f)
    private fun checkRows(dark: Boolean, scale: Float) {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Hong_Kong"))
        try {
            val longTitle = "这是一个很长的对话标题，用来检查时间是否仍然完整显示在最右侧"
            val samples = listOf(
                session("today", longTitle, "2026-10-26T06:35:27Z"),
                session("yesterday", "等待处理的对话", "2026-10-25T06:59:00Z", SessionStatus.WaitingApproval),
                session("last-year", "去年的对话", "2025-01-09T10:00:00Z"),
                session("unknown", "没有时间记录", null),
            )
            var clicked: String? = null
            compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.setContent {
                val density = LocalDensity.current
                val context = LocalContext.current
                val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
                CompositionLocalProvider(LocalContext provides chinese, LocalDensity provides Density(density.density, scale)) {
                    AgentsAnywhereTheme(if(dark) AAAppearanceMode.Dark else AAAppearanceMode.Light) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = LocalAAColors.current.canvas) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                                Text("项目下的对话")
                                samples.forEach { sample ->
                                    Box(Modifier.fillMaxWidth().testTag(sample.id)) {
                                        HomeProjectSessionRow(sample, onClick = { clicked = sample.id }, onLongPress = {}, now = Instant.parse("2026-10-26T07:00:00Z"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            capture(if(dark) "dark-large" else "light")
            for ((id,label) in listOf("today" to "14:35", "yesterday" to "10/25", "last-year" to "01/09", "unknown" to "—")) {
                compose.onNodeWithText(label).assertIsDisplayed()
                val row = compose.onNodeWithTag(id).fetchSemanticsNode().boundsInRoot
                val time = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue("$label must be at the right of the row", time.left > row.center.x && time.right <= row.right)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                // Compose retains the parent's paragraph width for unwrapped text;
                // check the visible glyph range against the measured Text viewport.
                assertEquals(label.length, layout.getLineEnd(0, visibleEnd = true))
                assertFalse(layout.isLineEllipsized(0))
                assertTrue("$label glyphs must fit completely", layout.getLineLeft(0) >= 0 && layout.getLineRight(0) <= layout.size.width)
                assertFalse(layout.didOverflowHeight)
            }
            compose.onNodeWithText("14:35").performTouchInput { click() }
            compose.runOnIdle { assertEquals("today", clicked) }
            compose.onNodeWithText(longTitle).assertIsDisplayed()
            capture(if(dark) "dark-large" else "light")
        } finally { TimeZone.setDefault(previousZone) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }
            view.invalidate()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "project-times-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
