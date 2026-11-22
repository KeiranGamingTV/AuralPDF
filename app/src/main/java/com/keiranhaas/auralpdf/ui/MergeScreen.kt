package com.keiranhaas.auralpdf.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*

@Composable
fun MergeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    val files = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files.addAll(it) }

    ToolScaffold("Merge PDFs", onBack) {
        Text("Add two or more PDFs, arrange them in order, then merge.")
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add PDFs")
        }
        files.forEachIndexed { i, uri ->
            val name = remember(uri) { ctx.displayName(uri) }
            val size = remember(uri) { formatSize(ctx.fileSize(uri)) }
            Card {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(size, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(enabled = i > 0, onClick = { files.add(i - 1, files.removeAt(i)) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = i < files.lastIndex, onClick = { files.add(i + 1, files.removeAt(i)) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { files.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
        Button(enabled = files.size >= 2 && !job.busy, onClick = {
            val picked = files.toList()
            job.launch(scope) {
                status = "Merging…"
                val inputs = picked.map { ctx.importToCache(it) }
                val out = OutputFiles.create(ctx, "merged.pdf")
                PdfOps.merge(inputs, out)
                OutFile(out, "application/pdf", "merged.pdf")
            }
        }) { Text("Merge ${files.size} PDFs") }
        JobPanel(job)
    }
}