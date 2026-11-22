package com.keiranhaas.auralpdf.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import java.io.File

@Composable
fun SplitScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    var source by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(0) }
    var ranges by remember { mutableStateOf("") }
    var chunk by remember { mutableStateOf("1") }
    var chunkFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.launch(scope) {
            status = "Opening…"
            val f = ctx.importToCache(uri)
            pageCount = PdfOps.pageCount(f)
            source = f
            chunkFiles = emptyList()
            null
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) job.launch(scope) {
            status = "Saving…"
            val dir = DocumentFile.fromTreeUri(ctx, tree) ?: error("Can't open that folder")
            chunkFiles.forEach { f ->
                val doc = dir.createFile("application/pdf", f.nameWithoutExtension) ?: error("Couldn't create ${f.name}")
                ctx.contentResolver.openOutputStream(doc.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
            message = "Saved ${chunkFiles.size} files to the chosen folder."
            null
        }
    }

    ToolScaffold("Split PDF", onBack) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text(if (source == null) "Choose a PDF" else "Choose a different PDF") }
        source?.let { src ->
            Text("${src.name} · $pageCount pages")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Extract pages") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Split into parts") })
            }
            if (mode == 0) {
                OutlinedTextField(
                    value = ranges, onValueChange = { ranges = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pages (e.g. 1-3, 5, 8-)") }, singleLine = true
                )
                Button(enabled = !job.busy, onClick = {
                    job.launch(scope) {
                        status = "Extracting…"
                        val pages = PdfOps.parseRanges(ranges, pageCount)
                        val name = "${src.nameWithoutExtension}_extract.pdf"
                        val out = OutputFiles.create(ctx, name)
                        PdfOps.extractPages(src, pages, out)
                        OutFile(out, "application/pdf", name, "${pages.size} pages")
                    }
                }) { Text("Extract") }
            } else {
                OutlinedTextField(
                    value = chunk, onValueChange = { chunk = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pages per part") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(enabled = !job.busy, onClick = {
                    job.launch(scope) {
                        status = "Splitting…"
                        val n = chunk.toIntOrNull()?.takeIf { it > 0 } ?: error("Enter 1 or more pages per part")
                        val dir = OutputFiles.tempDir(ctx)
                        chunkFiles = PdfOps.splitEvery(src, n, dir, src.nameWithoutExtension)
                        message = "Created ${chunkFiles.size} files. Choose a folder to save them."
                        null
                    }
                }) { Text("Split") }
                if (chunkFiles.isNotEmpty()) {
                    FilledTonalButton(enabled = !job.busy, onClick = { treePicker.launch(null) }) { Text("Choose folder & save ${chunkFiles.size} files") }
                }
            }
        }
        JobPanel(job)
    }
}