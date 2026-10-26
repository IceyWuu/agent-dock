package com.agentsanywhere.app.ui.screens.sessiondetail

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownScrollTest {
    @get:Rule val compose = createComposeRule()

    @Before fun initializeBundledMathFonts() {
        JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    private fun showHistory(older: String, darkMode: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    SessionSelectionContainer {
                        LazyColumn(Modifier.size(360.dp, 640.dp).testTag("history"), reverseLayout = true) {
                            items(60) { index ->
                                AgentMarkdownText(
                                    if (index >= 40) older + "\n\nOlder message $index."
                                    else "Recent message $index\n\nAn ordinary paragraph.",
                                    darkMode = darkMode,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scrollBackAndForth() {
        for (index in listOf(50, 0, 59, 15, 45, 0)) {
            compose.onNodeWithTag("history").performScrollToIndex(index)
            compose.waitForIdle()
            compose.onNodeWithTag("history").performTouchInput { swipeDown(durationMillis = 120) }
            compose.waitForIdle()
            compose.onNodeWithTag("history").performTouchInput { swipeUp(durationMillis = 120) }
            compose.waitForIdle()
        }
    }

    @Test fun scrollingIntoAnOlderPlainQuoteDoesNotCrash() {
        showHistory("> Older quoted reply\n> Ordinary text without formulas.")
        scrollBackAndForth()
    }

    @Test fun scrollingNestedQuotesWithFormattingAndTablesDoesNotCrash() {
        showHistory(
            "> ## Summary\n>\n> - **Bold** and `inline code`\n> - [Link](https://example.com)\n>\n" +
                "> > Nested quote\n>\n> | A | B |\n> | - | - |\n> | 1 | 2 |",
        )
        scrollBackAndForth()
    }

    @Test fun scrollingQuotesWithInlineAndDisplayFormulasDoesNotCrash() {
        showHistory("> Inline \\(x_1 + x_2\\).\n>\n> \\[\n> \\frac{a}{b} = \\sum_{i=1}^{n} x_i\n> \\]\n>\n> After math.")
        scrollBackAndForth()
    }

    @Test fun scrollingLongFormulasWithLargeFontsAndDarkThemeDoesNotCrash() {
        val formula = (1..100).joinToString(" + ") { "x_{$it}" }
        showHistory("> \\[\n> $formula\n> \\]\n>\n> A large-font reply.", darkMode = true, fontScale = 2f)
        scrollBackAndForth()
    }

    @Test fun mathEngineDrawsVisibleGlyphsInBothThemesAndFallsBackForInvalidTex() {
        for (color in listOf(Color.Black, Color.White)) {
            val math = MarkdownMathNode("\\frac{a_1}{b_2}", true, "\\[\\frac{a_1}{b_2}\\]")
            val drawable = MarkdownMathRenderer.render(math, 32f, color)
            assertNotNull(drawable)
            val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { it ushr 24 != 0 })
            bitmap.recycle()
        }
        val invalid = MarkdownMathNode("\\notAValidMathCommand{", true, "invalid")
        assertTrue(MarkdownMathRenderer.render(invalid, 32f, Color.Black) == null)
    }

    @Test fun multilineDisplayFormulaHasAVisibleLayoutRectangleAndCopiesOriginalTex() {
        val source = "\\[\n\\frac{a_1}{b_2} = \\sum_{i=1}^{n} x_i\n\\]"
        var copyTokens: Map<String, String>? = null
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).background(Color.White).testTag("formula")) {
                        SessionSelectionContainer {
                            val registry = LocalSessionSelectionCopyTokens.current
                            SideEffect { copyTokens = registry }
                            AgentMarkdownText(source, darkMode = false)
                        }
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithContentDescription(source).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val rectangle = layout.placeholderRects.single()
            assertNotNull("The formula's inline child must have a visible layout rectangle", rectangle)
            assertTrue("The formula viewport must stay inside the paragraph at any font scale", rectangle!!.right <= layout.size.width + 1)
            val density = Density(compose.density.density, scale)
            val drawable = MarkdownMathRenderer.render(
                MarkdownMathNode("\\frac{a_1}{b_2} = \\sum_{i=1}^{n} x_i", true, source),
                with(density) { 17.sp.toPx() }, Color.Black,
            )!!
            assertTrue("Placeholder height must match the glyphs without extra font scaling", abs(rectangle.height - drawable.intrinsicHeight) < 2f)
            val token = layout.layoutInput.text.text
            assertTrue("The internal layout token cannot contain paragraph breaks", '\n' !in token && '\r' !in token)
            assertEquals(source, copyTokens?.get(token))
        }
    }
}
