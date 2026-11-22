package com.keiranhaas.auralpdf.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import com.tom_roush.pdfbox.text.PDFTextStripper

@Composable
fun TextScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.launch(scope) {
            status = "Opening…"
            val f = ctx.importToCache(uri)
            val sb = StringBuilder()
            PdfOps.open(f).use { doc ->
                val n = doc.numberOfPages
                val stripper = PDFTextStripper()
                PdfPageRenderer(f).use { renderer ->
                    for (i in 0 until n) {
                        status = "Page ${i + 1} of $n"
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        var t = stripper.getText(doc).trim()
                        if (t.length < 10) { // no real text layer -> scanned page -> OCR
                            val bmp = renderer.render(i, 1600)
                            t = OcrEngine.recognize(bmp).text.trim()
                            bmp.recycle()
                        }
                        sb.append("--- Page ${i + 1} ---\n").append(t).append("\n\n")
                    }
                }
            }
            val name = "${f.nameWithoutExtension}.txt"
            val out = OutputFiles.create(ctx, name)
            out.writeText(sb.toString())
            OutFile(out, "text/plain", name)
        }
    }

    ToolScaffold("Extract text", onBack) {
        Text("Reads the text layer of a PDF. Pages that are only images (scans) are read with on-device OCR.")
        Button(enabled = !job.busy, onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Choose a PDF") }
        JobPanel(job)
    }
}