package com.agentsanywhere.app.ui.screens.sessiondetail

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.ui.designsystem.AAAppearanceMode
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run on an emulator/device: verifies the complete message renderer, including
 * placeholder placement and actual screen pixels, rather than just the TeX engine.
 */
@RunWith(AndroidJUnit4::class)
class MathPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestWindowAwake() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use { it.readBytes() }
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private data class Formula(val name: String, val latex: String, val multiline: Boolean = true) {
        val source get() = if (multiline) "\\[\n$latex\n\\]" else "\\[$latex\\]"
    }

    private val formulas = listOf(
        Formula("fraction", "\\frac{a_1}{b_2} = \\sum_{i=1}^{n} x_i"),
        Formula("single-line", "E = mc^2", multiline = false),
        Formula("fourier", "F(\\omega)=\\int_{-\\infty}^{\\infty} f(t)e^{-i\\omega t}\\,dt"),
        Formula("matrix", "\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}"),
        Formula("aligned", "\\begin{aligned}\nx_1 &= a + b \\\\\nx_2 &= c - d\n\\end{aligned}"),
        Formula("long", (1..60).joinToString(" + ") { "x_{$it}" }),
    )

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "current"
        val directory = File(context.getExternalFilesDir(null), "math-qa/$prefix").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capture(tag: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        val committed = CountDownLatch(1)
        compose.runOnUiThread {
            assertTrue("The fixture must be visible in the foreground", compose.activity.hasWindowFocus())
            val view = compose.activity.window.decorView
            view.getLocationOnScreen(origin)
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            } else {
                view.postOnAnimation { view.postOnAnimation { committed.countDown() } }
            }
            view.invalidate()
        }
        assertTrue("The screenshot must capture a committed display frame", committed.await(5, TimeUnit.SECONDS))
        // Capture the actual display compositor. This also works after host
        // sleep, when PixelCopy's window-surface callback can time out.
        val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val left = floor(bounds.left + origin[0]).toInt().coerceIn(0, screen.width - 1)
        val top = floor(bounds.top + origin[1]).toInt().coerceIn(0, screen.height - 1)
        val right = ceil(bounds.right + origin[0]).toInt().coerceIn(left + 1, screen.width)
        val bottom = ceil(bounds.bottom + origin[1]).toInt().coerceIn(top + 1, screen.height)
        return Bitmap.createBitmap(screen, left, top, right - left, bottom - top).also {
            if (it !== screen) screen.recycle()
        }
    }

    @Test fun displayMathActuallyAppears() {
        var formula by mutableStateOf(formulas.first())
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AgentsAnywhereTheme(AAAppearanceMode.Light) {
                    Box(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
                        Box(Modifier.width(340.dp).testTag("formula")) {
                            SessionSelectionContainer { AgentMarkdownText(formula.source, darkMode = false) }
                        }
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) for (sample in formulas) {
            compose.runOnIdle { formula = sample; fontScale = scale }
            compose.waitForIdle()
            assertNotNull(sample.name, MarkdownMathRenderer.render(MarkdownMathNode(sample.latex, true, sample.source), 32f, Color.Black))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithContentDescription(sample.source).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val rectangle = layout.placeholderRects.single()
            assertNotNull("${sample.name}: the formula must be placed", rectangle)
            assertTrue("${sample.name}: font scaling cannot expand the placeholder beyond its viewport", rectangle!!.right <= layout.size.width + 1)
            val bitmap = capture("formula")
            save(bitmap, "formula-${sample.name}-$scale")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("${sample.name}: the message must show the rendered formula", pixels.count {
                it ushr 24 != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF
            } > 30)
            if (sample.name == "long") {
                val scroller = compose.onNode(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNode()
                val range = scroller.config[SemanticsProperties.HorizontalScrollAxisRange]
                val before = range.value()
                val bounds = scroller.boundsInWindow
                val origin = IntArray(2)
                compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(origin) }
                val start = (bounds.left + bounds.width * 0.85f + origin[0]).toInt()
                val end = (bounds.left + bounds.width * 0.15f + origin[0]).toInt()
                val y = (bounds.center.y + origin[1]).toInt()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input swipe $start $y $end $y 300")).use { it.readBytes() }
                compose.waitForIdle()
                val scrolled = capture("formula")
                save(scrolled, "formula-long-scrolled-$scale")
                assertTrue("A long formula must respond to a real device swipe (before=$before, after=${range.value()}, max=${range.maxValue()}, bounds=$bounds)", range.value() > before && !bitmap.sameAs(scrolled))
            }
        }
    }

    private fun gallery(dark: Boolean, scale: Float) {
        val messages = listOf(
            "行内公式：\\(x_1 + x_2\\)，以及 \$\\omega = 2\\pi f\$。",
            formulas[0].source,
            formulas[2].source,
            formulas[3].source,
            formulas[4].source,
            "> 引用中的大公式：\n>\n> \\[\n> \\frac{1}{n}\\sum_{i=1}^{n}x_i\n> \\]",
            formulas[5].source,
            "| 公式 | 说明 |\n| - | - |\n| \\(x_1^2\\) | 表格中的公式 |",
            "代码保持原文：`\$x_1\$`，未知语法保留文本：\\[\\notAValidMathCommand{\\]",
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AgentsAnywhereTheme(if (dark) AAAppearanceMode.Dark else AAAppearanceMode.Light) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = LocalAAColors.current.canvas) {
                        SessionSelectionContainer {
                            LazyColumn(
                                Modifier.fillMaxSize().padding(20.dp).testTag("history"),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                itemsIndexed(messages) { index, message ->
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${index + 1}. 数学消息显示检查")
                                        AgentMarkdownText(message, darkMode = dark)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (index in listOf(0, 3, 5, 8, 0)) {
            compose.onNodeWithTag("history").performScrollToIndex(index)
            compose.waitForIdle()
            save(capture("history"), "gallery-${if (dark) "dark" else "light"}-$scale-$index")
            compose.onNodeWithTag("history").performTouchInput { swipeUp(durationMillis = 200) }
            compose.onNodeWithTag("history").performTouchInput { swipeDown(durationMillis = 200) }
            compose.waitForIdle()
        }
    }

    @Test fun galleryInLightTheme() = gallery(dark = false, scale = 1f)
    @Test fun galleryInDarkTheme() = gallery(dark = true, scale = 1f)
    @Test fun galleryWithLargeFonts() = gallery(dark = false, scale = 2f)
}
