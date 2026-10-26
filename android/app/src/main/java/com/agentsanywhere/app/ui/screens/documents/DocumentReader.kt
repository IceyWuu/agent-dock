package com.agentsanywhere.app.ui.screens.documents

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.files.*
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.screens.sessiondetail.AgentMarkdownDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DocumentReader(
    document: WorkspaceDocument,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {},
    loadImage: suspend (String) -> File? = { null },
) {
    val context = LocalContext.current
    val store = remember(context) { DocumentReadingStore(context) }
    val colors = LocalAAColors.current
    val kind = remember(document.name) { workspaceFileKind(document.name) }
    DisposableEffect(document.file) {
        WorkspaceDocumentCache.pin(document.file)
        onDispose { WorkspaceDocumentCache.unpin(document.file) }
    }
    key(document.identity) {
        var source by rememberSaveable { mutableStateOf(store.sourceMode(document.identity)) }
        Column(modifier.fillMaxSize().background(colors.canvas)) {
            if (kind == WorkspaceFileKind.Markdown) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilterChip(!source, { source = false; store.saveSourceMode(document.identity, false) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.subtle, selectedLabelColor = colors.ink), label = { Text(stringResource(R.string.document_read)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(source, { source = true; store.saveSourceMode(document.identity, true) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.subtle, selectedLabelColor = colors.ink), label = { Text(stringResource(R.string.document_source)) })
                }
            }
            when (kind) {
                WorkspaceFileKind.Pdf -> PdfDocument(document, store, Modifier.weight(1f))
                WorkspaceFileKind.Image -> ZoomSurface(Modifier.weight(1f)) {
                    AsyncImage(document.file, document.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                else -> {
                    var text by remember(document.file) { mutableStateOf<String?>(null) }
                    var failed by remember(document.file) { mutableStateOf(false) }
                    LaunchedEffect(document.file) {
                        runCatching { withContext(Dispatchers.IO) {
                            require(document.file.length() <= 8 * 1024 * 1024) // Bound text decoding and parsing.
                            val value = document.file.readText()
                            require(!value.take(8192).contains('\u0000'))
                            value
                        } }.onSuccess { text = it }.onFailure { failed = true }
                    }
                    if (failed) ReaderStatus(stringResource(R.string.document_unsupported))
                    else if (text == null) ReaderStatus(stringResource(R.string.files_loading_file))
                    else if (kind == WorkspaceFileKind.Markdown && !source) {
                        val list = rememberLazyListState(store.position(document.identity, "read-index"), store.position(document.identity, "read-offset"))
                        DisposableEffect(list) { onDispose {
                            store.savePosition(document.identity, "read-index", list.firstVisibleItemIndex)
                            store.savePosition(document.identity, "read-offset", list.firstVisibleItemScrollOffset)
                        } }
                        LaunchedEffect(list) {
                            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                                store.savePosition(document.identity, "read-index", index)
                                store.savePosition(document.identity, "read-offset", offset)
                            }
                        }
                        AgentMarkdownDocument(text!!, colors.isDark, list, onOpenFile, loadImage, Modifier.weight(1f))
                    } else {
                        val list = rememberLazyListState(store.position(document.identity, "source-index"), store.position(document.identity, "source-offset"))
                        val chunks = remember(text) { text!!.lines().flatMap { it.chunked(2048).ifEmpty { listOf("") } }.chunked(24).map { it.joinToString("\n") } }
                        DisposableEffect(list) { onDispose {
                            store.savePosition(document.identity, "source-index", list.firstVisibleItemIndex)
                            store.savePosition(document.identity, "source-offset", list.firstVisibleItemScrollOffset)
                        } }
                        androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("document-source"), contentPadding = PaddingValues(16.dp)) {
                            items(chunks.size) { index -> SelectionContainer {
                                Text(chunks[index], color = colors.ink, fontFamily = FontFamily.Monospace)
                            } }
                        }
                    }
                }
            }
        }
    }
}

// Kept here so PDF rendering never depends on a browser's built-in PDF support.
@Composable
private fun PdfDocument(document: WorkspaceDocument, store: DocumentReadingStore, modifier: Modifier) {
    var page by rememberSaveable(document.identity) { mutableIntStateOf(store.position(document.identity, "pdf")) }
    var count by remember(document.file) { mutableIntStateOf(0) }
    var bitmap by remember(document.file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(document.file) { mutableStateOf(false) }
    LaunchedEffect(document.file, page) {
        failed = false
        bitmap = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val index = page.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                        renderer.openPage(index).use { pdfPage ->
                            val scale = minOf(1600f / pdfPage.width, 2200f / pdfPage.height)
                            val image = Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1), (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            Triple(renderer.pageCount, index, image)
                        }
                    }
                }
            }
            count = rendered.first
            page = rendered.second
            bitmap = rendered.third
            store.savePosition(document.identity, "pdf", page)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failed = true
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton({ page-- }, enabled = page > 0 && bitmap != null) { Text(stringResource(R.string.document_previous)) }
            Text(if (count > 0) "${page + 1} / $count" else "—", color = LocalAAColors.current.ink)
            TextButton({ page++ }, enabled = page + 1 < count && bitmap != null) { Text(stringResource(R.string.document_next)) }
        }
        when {
            failed -> ReaderStatus(stringResource(R.string.document_pdf_failed))
            bitmap == null -> ReaderStatus(stringResource(R.string.files_loading_file))
            else -> key(page) { ZoomSurface(Modifier.weight(1f)) {
                Image(bitmap!!.asImageBitmap(), document.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } }
        }
    }
}

@Composable
private fun ZoomSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({ scale = (scale / 1.4f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero }) { Text("−") }
            TextButton({ scale = 1f; offset = Offset.Zero }) { Text("${(scale * 100).toInt()}%") }
            TextButton({ scale = (scale * 1.4f).coerceAtMost(6f) }) { Text("+") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(transform)) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) { content() }
        }
    }
}

@Composable
internal fun ReaderStatus(message: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(message, color = LocalAAColors.current.muted)
    }
}
