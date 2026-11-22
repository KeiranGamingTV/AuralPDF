package com.keiranhaas.auralpdf.data

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text as MlText
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

enum class FieldType { TEXT, CHECK, CHOICE }

data class FormFieldInfo(
    val name: String,
    val type: FieldType,
    val options: List<String>,
    val value: String,
    val multiline: Boolean = false
)

/** A signature/text image placed on a page. cx/cy are the stamp centre as fractions (0..1) of the *displayed* page. */
class Stamp(val page: Int, val cx: Float, val cy: Float, val widthFrac: Float, val bitmap: Bitmap)

class ScannedPage(val jpeg: File, val width: Int, val height: Int, val text: MlText?)

enum class CompressLevel(val label: String, val quality: Int, val maxDim: Int) {
    LIGHT("Light (best quality)", 85, 2400),
    BALANCED("Balanced", 70, 1600),
    STRONG("Strong (smallest file)", 50, 1100)
}

object PdfOps {
    private fun mem() = MemoryUsageSetting.setupMixed(32L * 1024 * 1024)

    internal fun open(file: File): PDDocument = PDDocument.load(file, mem())

    fun pageCount(file: File): Int = open(file).use { it.numberOfPages }

    // ---------- Merge ----------
    fun merge(inputs: List<File>, out: File) {
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        merger.destinationFileName = out.absolutePath
        merger.mergeDocuments(mem())
    }

    // ---------- Split / extract ----------
    /** "1-3, 5, 8-" -> zero-based page indices, in the order written. */
    fun parseRanges(spec: String, pageCount: Int): List<Int> {
        fun bad(p: String): Nothing = throw IllegalArgumentException("Can't read \"$p\"")
        val result = mutableListOf<Int>()
        for (raw in spec.split(',')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val bits = part.split('-').map { it.trim() }
            val (a, b) = when (bits.size) {
                1 -> {
                    val n = bits[0].toIntOrNull() ?: bad(part)
                    n to n
                }
                2 -> {
                    val s = if (bits[0].isEmpty()) 1 else bits[0].toIntOrNull() ?: bad(part)
                    val e = if (bits[1].isEmpty()) pageCount else bits[1].toIntOrNull() ?: bad(part)
                    s to e
                }
                else -> bad(part)
            }
            require(a >= 1 && b <= pageCount && a <= b) { "Range \"$part\" is outside 1–$pageCount" }
            for (p in a..b) result += p - 1
        }
        require(result.isNotEmpty()) { "Enter at least one page or range" }
        return result
    }

    fun extractPages(input: File, pages: List<Int>, out: File) {
        open(input).use { src ->
            PDDocument(mem()).use { dst ->
                pages.forEach { dst.importPage(src.getPage(it)) }
                dst.save(out)
            }
        }
    }

    fun splitEvery(input: File, n: Int, outDir: File, baseName: String): List<File> {
        val files = mutableListOf<File>()
        open(input).use { doc ->
            val splitter = Splitter().apply { setSplitAtPage(n) }
            val parts = splitter.split(doc)
            try {
                parts.forEachIndexed { i, part ->
                    val f = File(outDir, "${baseName}_part${i + 1}.pdf")
                    part.save(f)
                    files += f
                }
            } finally {
                parts.forEach { runCatching { it.close() } }
            }
        }
        return files
    }

    // ---------- Compress (re-encodes and downsamples images) ----------
    fun compress(input: File, out: File, level: CompressLevel, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        open(input).use { doc ->
            val worker = Recompressor(doc, level)
            val total = doc.numberOfPages
            for ((i, page) in doc.pages.withIndex()) {
                onProgress(i + 1, total)
                page.resources?.let { worker.process(it) }
            }
            doc.save(out)
        }
    }

    private class Recompressor(val doc: PDDocument, val level: CompressLevel) {
        private val images = IdentityHashMap<COSBase, PDImageXObject?>()
        private val forms = IdentityHashMap<COSBase, Boolean>()

        fun process(res: PDResources) {
            for (name in res.xObjectNames.toList()) {
                val xo = try { res.getXObject(name) } catch (_: Throwable) { continue }
                when (xo) {
                    is PDFormXObject -> {
                        if (forms.put(xo.cosObject, true) == null) xo.resources?.let { process(it) }
                    }
                    is PDImageXObject -> {
                        val key = xo.cosObject
                        if (images.containsKey(key)) {
                            images[key]?.let { res.put(name, it) }
                            continue
                        }
                        val replacement = try { shrink(xo) } catch (_: Throwable) { null }
                        images[key] = replacement
                        replacement?.let { res.put(name, it) }
                    }
                    else -> Unit
                }
            }
        }

        private fun shrink(img: PDImageXObject): PDImageXObject? {
            if (img.isStencil || img.softMask != null || img.mask != null) return null
            val originalBytes = img.cosObject.length
            var bmp = img.image ?: return null
            val longest = max(bmp.width, bmp.height)
            if (longest > level.maxDim) {
                val s = level.maxDim.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
                    (bmp.height * s).roundToInt().coerceAtLeast(1), true
                )
            }
            bmp.setHasAlpha(false)
            val candidate = JPEGFactory.createFromImage(doc, bmp, level.quality / 100f)
            return if (candidate.cosObject.length < originalBytes) candidate else null
        }
    }

    // ---------- Sign / stamp ----------
    fun applyStamps(input: File, out: File, stamps: List<Stamp>) {
        open(input).use { doc ->
            val cache = IdentityHashMap<Bitmap, PDImageXObject>()
            stamps.groupBy { it.page }.forEach { (pageIdx, list) ->
                val page = doc.getPage(pageIdx)
                val box = page.cropBox
                val rot = ((page.rotation % 360) + 360) % 360
                val w = box.width
                val h = box.height
                val dispW = if (rot == 90 || rot == 270) h else w
                val dispH = if (rot == 90 || rot == 270) w else h

                // displayed point (from top-left, in points) -> PDF user space
                fun toUser(dx: Float, dy: Float): Pair<Float, Float> {
                    val (ux, uy) = when (rot) {
                        90 -> dy to dx
                        180 -> (w - dx) to dy
                        270 -> (w - dy) to (h - dx)
                        else -> dx to (h - dy)
                    }
                    return (box.lowerLeftX + ux) to (box.lowerLeftY + uy)
                }

                for (s in list) {
                    val img = cache.getOrPut(s.bitmap) { LosslessFactory.createFromImage(doc, s.bitmap) }
                    val sw = s.widthFrac * dispW
                    val sh = sw * s.bitmap.height / s.bitmap.width
                    val left = s.cx * dispW - sw / 2
                    val top = s.cy * dispH - sh / 2
                    val (e, f) = toUser(left, top + sh)          // image bottom-left
                    val (bx, by) = toUser(left + sw, top + sh)   // image bottom-right
                    val (tx, ty) = toUser(left, top)             // image top-left
                    val m = PdfMatrix(bx - e, by - f, tx - e, ty - f, e, f)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.drawImage(img, m)
                    }
                }
            }
            doc.save(out)
        }
    }

    // ---------- Forms ----------
    fun readForm(file: File): List<FormFieldInfo> = open(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return@use emptyList()
        form.fieldTree.mapNotNull { f ->
            when (f) {
                is PDTextField -> FormFieldInfo(f.fullyQualifiedName, FieldType.TEXT, emptyList(), f.value ?: "", f.isMultiline)
                is PDCheckBox -> FormFieldInfo(f.fullyQualifiedName, FieldType.CHECK, emptyList(), f.isChecked.toString())
                is PDRadioButton -> FormFieldInfo(f.fullyQualifiedName, FieldType.CHOICE, f.onValues.toList(), f.value ?: "")
                is PDComboBox -> FormFieldInfo(f.fullyQualifiedName, FieldType.CHOICE, f.options, f.value.firstOrNull() ?: "")
                is PDListBox -> FormFieldInfo(f.fullyQualifiedName, FieldType.CHOICE, f.options, f.value.firstOrNull() ?: "")
                else -> null
            }
        }
    }

    /** Returns a warning message, or null. */
    fun writeForm(input: File, out: File, values: Map<String, String>, flatten: Boolean): String? {
        var note: String? = null
        open(input).use { doc ->
            val form = doc.documentCatalog.acroForm ?: error("This PDF has no form fields")
            for ((name, v) in values) {
                val field = form.getField(name) ?: continue
                try {
                    when (field) {
                        is PDCheckBox -> if (v == "true") field.check() else field.unCheck()
                        else -> field.setValue(v)
                    }
                } catch (_: Throwable) {
                    // Appearance generation failed; let the viewer draw the field instead.
                    form.setNeedAppearances(true)
                }
            }
            if (flatten) {
                try { form.flatten() } catch (_: Throwable) { note = "Couldn't flatten the form; fields are still editable." }
            }
            doc.save(out)
        }
        return note
    }

    // ---------- Scans -> searchable PDF ----------
    private fun sanitize(s: String): String = buildString {
        for (c in s) append(if (c.code in 32..126 || c.code in 161..255) c else ' ')
    }

    fun buildScanPdf(pages: List<ScannedPage>, out: File) {
        PDDocument().use { doc ->
            val font = PDType1Font.HELVETICA
            for (p in pages) {
                val pw = 595f
                val s = pw / p.width
                val ph = p.height * s
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val img = JPEGFactory.createFromByteArray(doc, p.jpeg.readBytes())
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, 0f, 0f, pw, ph)
                    p.text?.textBlocks?.forEach { block ->
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val txt = sanitize(line.text)
                            if (txt.isBlank()) continue
                            val size = (box.height() * s).coerceIn(4f, 72f)
                            val natural = font.getStringWidth(txt) / 1000f * size
                            if (natural <= 0f) continue
                            val sx = (box.width() * s) / natural
                            val x = box.left * s
                            val y = ph - box.bottom * s + size * 0.2f
                            cs.beginText()
                            cs.setRenderingMode(RenderingMode.NEITHER) // invisible text layer
                            cs.setFont(font, size)
                            cs.setTextMatrix(PdfMatrix(sx, 0f, 0f, 1f, x, y))
                            cs.showText(txt)
                            cs.endText()
                        }
                    }
                }
            }
            doc.save(out)
        }
    }
}