package com.agentsanywhere.app.ui.screens.sessiondetail

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.UUID

/** Bounded cache avoids reparsing completed formulas on every streamed append.
 * JLatexMath's manifest provider initializes its bundled fonts at app startup.
 */
internal object MarkdownMathRenderer {
    private data class Key(val latex: String, val display: Boolean, val size: Float, val color: Int)
    private class CachedMath(val drawable: JLatexMathDrawable?)
    private val cache = LruCache<Key, CachedMath>(48)

    @Synchronized
    fun render(math: MarkdownMathNode, size: Float, color: Color): JLatexMathDrawable? {
        val key = Key(math.latex, math.display, size, color.toArgb())
        cache.get(key)?.let { return it.drawable }
        // Unsupported or malformed TeX must never hide the rest of a response.
        val drawable = runCatching {
            JLatexMathDrawable.builder(if (math.display) math.latex else "\\textstyle ${math.latex}")
                .textSize(size)
                .color(key.color)
                .padding(2)
                .build()
        }.getOrNull()
        cache.put(key, CachedMath(drawable))
        return drawable
    }
}

// Android's PlaceholderSpan resolves sp linearly using density * fontScale,
// unlike Density.toSp(), which uses nonlinear font scaling on Android 14+.
// Invert the span's conversion so a measured formula keeps its actual bounds.
private fun Density.placeholderSize(pixels: Int) = (pixels / (density * fontScale)).sp

internal fun mathInlineContent(
    math: MarkdownMathNode,
    style: TextStyle,
    density: Density,
    maxWidthPx: Int,
): InlineTextContent? {
    val drawable = MarkdownMathRenderer.render(math, with(density) { style.fontSize.toPx() }, style.color) ?: return null
    val width = drawable.intrinsicWidth.coerceIn(1, maxWidthPx.coerceAtLeast(1))
    return InlineTextContent(
        placeholder = Placeholder(
            density.placeholderSize(width),
            density.placeholderSize(drawable.intrinsicHeight.coerceAtLeast(1)),
            PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        MathImage(drawable)
    }
}

@Composable
internal fun MarkdownMathBlock(math: MarkdownMathNode, style: TextStyle) {
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toPx() }
    val drawable = remember(math.latex, math.display, size, style.color) {
        MarkdownMathRenderer.render(math, size, style.color)
    }
    if (drawable == null) {
        Text(math.source, style = style)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val token = remember(math.source) { "[[AA_SELECT_MATH:${UUID.randomUUID()}]]" }
        RegisterSessionSelectionCopyToken(token, math.source)
        // An inline placeholder cannot span paragraph breaks. Using multiline
        // TeX as alternate text makes Android drop its rectangle and child,
        // leaving invisible source text. Keep the layout token on one line and
        // restore the original TeX through the selection clipboard instead.
        val text = remember(token) {
            buildAnnotatedString { appendInlineContent(token, token) }
        }
        val contentWidth = maxOf(constraints.maxWidth, drawable.intrinsicWidth + 1)
        // Keep the scroll gesture outside BasicText's inline child/selection
        // handling. The text and attachment move together in one viewport.
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BasicText(
                text = text,
                modifier = Modifier
                    .width(with(density) { contentWidth.toDp() })
                    .semantics { contentDescription = math.source },
                style = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                inlineContent = mapOf(
                    token to InlineTextContent(
                        Placeholder(
                            density.placeholderSize((contentWidth - 1).coerceAtLeast(1)),
                            density.placeholderSize(drawable.intrinsicHeight.coerceAtLeast(1)),
                            PlaceholderVerticalAlign.TextTop,
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MathCanvas(drawable) }
                    },
                ),
            )
        }
    }
}

@Composable
private fun MathImage(drawable: JLatexMathDrawable) {
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        MathCanvas(drawable)
    }
}

@Composable
private fun MathCanvas(drawable: JLatexMathDrawable) {
    val density = LocalDensity.current
    Canvas(
        Modifier.size(
            with(density) { drawable.intrinsicWidth.coerceAtLeast(1).toDp() },
            with(density) { drawable.intrinsicHeight.coerceAtLeast(1).toDp() },
        ),
    ) {
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}
