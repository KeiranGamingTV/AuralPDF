package com.keiranhaas.auralpdf.data

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScanFilter(val label: String) {
    ORIGINAL("Original"), GRAY("Grayscale"), BW("Black & white")
}

object DocScanner {
    @Volatile private var loaded = false

    private fun ready(): Boolean {
        if (!loaded) loaded = OpenCVLoader.initLocal()
        return loaded
    }

    /** Returns corners ordered TL, TR, BR, BL in [src] pixel coordinates. */
    fun detect(src: Bitmap): List<PointF> {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val fallback = listOf(
            PointF(w * 0.05f, h * 0.05f), PointF(w * 0.95f, h * 0.05f),
            PointF(w * 0.95f, h * 0.95f), PointF(w * 0.05f, h * 0.95f)
        )
        if (!ready()) return fallback

        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scale = 700.0 / max(src.width, src.height)
        val small = Mat()
        Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = small.width() * small.height() * 0.15
        var best: List<PointF>? = null
        for (c in contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)) {
            if (Imgproc.contourArea(c) < minArea) break
            val c2 = MatOfPoint2f(*c.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2, approx, 0.02 * Imgproc.arcLength(c2, true), true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                best = order(approx.toArray().map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) })
                break
            }
        }
        listOf(rgba, small, gray, edges).forEach { it.release() }
        return best ?: fallback
    }

    private fun order(p: List<PointF>): List<PointF> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    /** Flattens the quad [c] (TL, TR, BR, BL) into an upright rectangle. */
    fun warp(src: Bitmap, c: List<PointF>): Bitmap {
        if (!ready()) return src
        fun d(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        val outW = max(d(c[0], c[1]), d(c[3], c[2])).roundToInt().coerceAtLeast(1)
        val outH = max(d(c[0], c[3]), d(c[1], c[2])).roundToInt().coerceAtLeast(1)

        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val from = MatOfPoint2f(*c.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(rgba, out, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        listOf(rgba, out, m, from, to).forEach { it.release() }
        return bmp
    }

    fun filter(src: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.ORIGINAL || !ready()) return src
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val res = Mat()
        if (f == ScanFilter.BW) {
            Imgproc.adaptiveThreshold(
                gray, res, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 41, 12.0
            )
        } else {
            Core.normalize(gray, res, 0.0, 255.0, Core.NORM_MINMAX)
        }
        Imgproc.cvtColor(res, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        listOf(rgba, gray, res).forEach { it.release() }
        return bmp
    }
}