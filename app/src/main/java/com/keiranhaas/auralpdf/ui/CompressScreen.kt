package com.keiranhaas.auralpdf.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import java.io.File

@Composable
fun CompressScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    var source by remember { mutableStateOf<File?>(null) }
    var level by remember { mutableStateOf(CompressLevel.BALANCED) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.launch(scope) { status = "Opening…"; source = ctx.importToCache(uri); null }
    }

    ToolScaffold("Compress PDF", onBack) {
        Text("Compression downsamples and re-encodes images. Text and vector content are left untouched.")
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text(if (source == null) "Choose a PDF" else "Choose a different PDF") }
        source?.let { src ->
            Text("${src.name} · ${formatSize(src.length())}")
            CompressLevel.entries.forEach { l ->
                FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
            }
            Button(enabled = !job.busy, onClick = {
                job.launch(scope) {
                    val name = "${src.nameWithoutExtension}_compressed.pdf"
                    val out = OutputFiles.create(ctx, name)
                    PdfOps.compress(src, out, level) { i, n -> status = "Page $i of $n" }
                    val before = src.length()
                    var after = out.length()
                    val note = if (after >= before) {
                        src.copyTo(out, overwrite = true)
                        after = before
                        "This PDF is already well optimized; no savings were possible."
                    } else {
                        "Reduced from ${formatSize(before)} to ${formatSize(after)} (${100 - after * 100 / before}% smaller)."
                    }
                    OutFile(out, "application/pdf", name, note)
                }
            }) { Text("Compress") }
        }
        JobPanel(job)
    }
}