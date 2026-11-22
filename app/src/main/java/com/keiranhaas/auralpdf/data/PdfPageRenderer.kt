package com.keiranhaas.auralpdf.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** Renders pages of a local PDF file with the platform renderer. */
class PdfPageRenderer(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap {
        check(!closed) { "Renderer closed" }
        renderer.openPage(index).use { page ->
            val h = (targetWidth.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}