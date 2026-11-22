package com.keiranhaas.auralpdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val INK = 0xFF0B2A6F.toInt()

/** Builds transparent bitmaps for signatures and text stamps. */
object StampBitmaps {

    fun fromStrokes(strokes: List<List<Offset>>): Bitmap? {
        val pts = strokes.flatten()
        if (pts.size < 2) return null
        val pad = 12f
        val minX = pts.minOf { it.x } - pad
        val minY = pts.minOf { it.y } - pad
        val maxX = pts.maxOf { it.x } + pad
        val maxY = pts.maxOf { it.y } + pad
        val up = 3f
        val bmp = Bitmap.createBitmap(
            ((maxX - minX) * up).roundToInt().coerceAtLeast(1),
            ((maxY - minY) * up).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.STROKE
            strokeWidth = 6f * up
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            if (s.size < 2) continue
            val path = Path()
            s.forEachIndexed { i, o ->
                val x = (o.x - minX) * up
                val y = (o.y - minY) * up
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    fun text(s: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val lines = s.lines()
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val w = lines.maxOf { paint.measureText(it) }.roundToInt() + 16
        val h = (lineH * lines.size).roundToInt() + 16
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        lines.forEachIndexed { i, line -> canvas.drawText(line, 8f, 8f + i * lineH - fm.ascent, paint) }
        return bmp
    }

    /** Turns a photo of a signature on paper into ink on a transparent background. */
    fun fromPhoto(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > 1200) d.setTargetSampleSize(ceil(m / 1200.0).toInt())
        }
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        for (i in px.indices) {
            val c = px[i]
            val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            px[i] = if (lum > 230) 0
            else Color.argb(((230 - lum) / 230 * 255 * 1.3).coerceIn(0.0, 255.0).toInt(), 11, 42, 111)
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, src.width, 0, 0, src.width, src.height)
        return out
    }
}

/** The saved signature lives only in the app's private storage. */
object SignatureStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "signature.png")

    fun load(ctx: Context): Bitmap? =
        file(ctx).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }

    fun save(ctx: Context, bmp: Bitmap) {
        file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
    }
}