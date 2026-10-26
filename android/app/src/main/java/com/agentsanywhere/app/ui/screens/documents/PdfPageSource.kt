package com.agentsanywhere.app.ui.screens.documents

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class RenderedPdfPage(val count: Int, val index: Int, val bitmap: Bitmap)

/** One renderer per opened document, bounded to two page bitmaps, never all pages. */
internal class PdfPageSource(private val file: File) {
    private val lock = Any()
    @Volatile private var closing = false
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val pages = LinkedHashMap<Int, Bitmap>(2, 0.75f, true)

    fun render(requested: Int): RenderedPdfPage = synchronized(lock) {
        check(!closing) { "PDF reader is closed." }
        val started = SystemClock.elapsedRealtime()
        if (renderer == null) {
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                renderer = PdfRenderer(opened)
                descriptor = opened
            } catch (error: Exception) { opened.close(); throw error }
            Log.i("DocumentTiming", "phase=pdf-open elapsedMs=${SystemClock.elapsedRealtime() - started} pages=${renderer!!.pageCount}")
        }
        val pdf = renderer!!
        require(pdf.pageCount > 0)
        val index = requested.coerceIn(0, pdf.pageCount - 1)
        val existing = pages[index]
        if (existing != null) return@synchronized RenderedPdfPage(pdf.pageCount, index, existing)
        val image = pdf.openPage(index).use { page ->
            val scale = minOf(1600f / page.width, 2200f / page.height)
            Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.WHITE)
                try { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                catch (error: Exception) { it.recycle(); throw error }
            }
        }
        pages[index] = image
        if (pages.size > 2) pages.remove(pages.keys.first())
        Log.i("DocumentTiming", "phase=pdf-page elapsedMs=${SystemClock.elapsedRealtime() - started} page=${index + 1}")
        RenderedPdfPage(pdf.pageCount, index, image)
    }

    fun dispose() {
        closing = true
        // Wait for any in-flight native render off the UI thread. Bitmaps are
        // released by GC after Compose's last frame stops referencing them.
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(lock) {
                pages.clear()
                try { renderer?.close() } finally { descriptor?.close(); renderer = null; descriptor = null }
            }
        }
    }
}
