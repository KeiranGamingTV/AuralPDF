package com.keiranhaas.auralpdf.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.keiranhaas.auralpdf.data.formatSize
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OutFile(val file: File, val mime: String, val displayName: String, val note: String? = null)

/** Runs one background job and exposes progress / error / result to the UI. */
class JobState {
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var result by mutableStateOf<OutFile?>(null)

    fun launch(scope: CoroutineScope, block: suspend JobState.() -> OutFile?) {
        if (busy) return
        busy = true; error = null; message = null; result = null
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { this@JobState.block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidPasswordException) {
                error = "This PDF is password-protected. Remove the password first."
            } catch (e: Throwable) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                busy = false; status = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun JobPanel(job: JobState) {
    if (job.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        job.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    job.message?.let { Text(it) }
    job.result?.let { ResultCard(it) }
    Spacer(Modifier.height(24.dp))
}

@Composable
fun ResultCard(out: OutFile) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(out.mime)) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> out.file.inputStream().use { it.copyTo(o) } }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
    fun uri() = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out.file)
    val isText = out.mime == "text/plain"
    val preview = remember(out) { if (isText) out.file.readText().take(4000) else null }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
            Text("${out.displayName} · ${formatSize(out.file.length())}")
            out.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            preview?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saver.launch(out.displayName) }) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save")
                }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = out.mime
                        putExtra(Intent.EXTRA_STREAM, uri())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, null))
                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                if (isText) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(out.file.readText()))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                } else {
                    OutlinedButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri(), out.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { ctx.startActivity(view) } catch (_: ActivityNotFoundException) {
                            Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Open") }
                }
            }
        }
    }
}