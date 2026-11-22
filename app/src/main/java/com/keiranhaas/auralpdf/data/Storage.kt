package com.keiranhaas.auralpdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.camera.core.ImageProxy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object OutputFiles {
    private fun outDir(ctx: Context) = File(ctx.cacheDir, "out").apply { mkdirs() }

    /** A fresh file with a pretty name, inside its own unique folder. */
    fun create(ctx: Context, name: String): File {
        val dir = File(outDir(ctx), System.nanoTime().toString()).apply { mkdirs() }
        return File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    }

    fun tempDir(ctx: Context): File =
        File(outDir(ctx), "tmp_${System.nanoTime()}").apply { mkdirs() }

    fun clearAll(ctx: Context) {
        listOf("out", "in", "scan").forEach { File(ctx.cacheDir, it).deleteRecursively() }
    }
}

/** Copies a picked document into app cache so PdfBox/PdfRenderer get a real, seekable file. */
fun Context.importToCache(uri: Uri): File {
    val dir = File(cacheDir, "in/${System.nanoTime()}").apply { mkdirs() }
    val file = File(dir, displayName(uri).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { input.copyTo(it) }
    } ?: error("Couldn't open the selected file")
    return file
}

fun Context.displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "document.pdf"

fun Context.fileSize(uri: Uri): Long =
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1048576.0)
}

fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Scan-page helpers: pages live as JPEG files so many pages don't exhaust RAM. */
object ScanStore {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "scan").apply { mkdirs() }

    fun newFile(ctx: Context, prefix: String) = File(dir(ctx), "${prefix}_${System.nanoTime()}.jpg")

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun load(file: File): Bitmap = BitmapFactory.decodeFile(file.path)

    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    fun thumb(file: File, maxDim: Int = 320): Bitmap? {
        val (w, h) = size(file)
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun limit(b: Bitmap, maxDim: Int = 3000): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxDim) return b
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
    }

    /** Converts a CameraX capture into an upright JPEG file. Always closes [image]. */
    fun saveCapture(ctx: Context, image: ImageProxy): File = try {
        var bmp = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) {
            val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        bmp = limit(bmp)
        newFile(ctx, "raw").also { saveJpeg(bmp, it, 95) }
    } finally {
        image.close()
    }

    fun importImage(ctx: Context, uri: Uri): File {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > 3000) decoder.setTargetSampleSize(ceil(m / 3000.0).toInt())
        }
        return newFile(ctx, "raw").also { saveJpeg(bmp, it, 95) }
    }
}