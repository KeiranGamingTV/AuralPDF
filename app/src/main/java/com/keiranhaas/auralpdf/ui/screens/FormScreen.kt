package com.keiranhaas.auralpdf.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import java.io.File

private class FieldState(val info: FormFieldInfo) {
    var value by mutableStateOf(info.value)
}

@Composable
fun FormScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    val fields = remember { mutableStateListOf<FieldState>() }
    var src by remember { mutableStateOf<File?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var flatten by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.launch(scope) {
            status = "Reading form…"
            val f = ctx.importToCache(uri)
            val list = PdfOps.readForm(f)
            src = f
            fields.clear(); fields.addAll(list.map(::FieldState))
            loaded = true
            null
        }
    }

    ToolScaffold("Fill forms", onBack) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text(if (src == null) "Choose a PDF form" else "Choose a different PDF") }
        if (loaded && fields.isEmpty()) {
            Text("This PDF has no fillable form fields. Use Sign & annotate to type text anywhere on a page instead.")
        }
        fields.forEach { fs ->
            when (fs.info.type) {
                FieldType.TEXT -> OutlinedTextField(
                    value = fs.value, onValueChange = { fs.value = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(fs.info.name) }, singleLine = !fs.info.multiline
                )
                FieldType.CHECK -> Row(
                    Modifier.fillMaxWidth().clickable { fs.value = (fs.value != "true").toString() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = fs.value == "true", onCheckedChange = { fs.value = it.toString() })
                    Text(fs.info.name)
                }
                FieldType.CHOICE -> ChoiceField(fs)
            }
        }
        if (fields.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = flatten, onCheckedChange = { flatten = it })
                Spacer(Modifier.width(12.dp))
                Text("Flatten (lock the filled values)")
            }
            Button(enabled = !job.busy, onClick = {
                val file = src!!
                val values = fields.associate { it.info.name to it.value }
                job.launch(scope) {
                    status = "Filling…"
                    val name = "${file.nameWithoutExtension}_filled.pdf"
                    val out = OutputFiles.create(ctx, name)
                    val note = PdfOps.writeForm(file, out, values, flatten)
                    OutFile(out, "application/pdf", name, note)
                }
            }) { Text("Save filled PDF") }
        }
        JobPanel(job)
    }
}

@Composable
private fun ChoiceField(fs: FieldState) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(fs.info.name, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(fs.value.ifEmpty { "Select…" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fs.info.options.forEach { o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { fs.value = o; open = false })
                }
            }
        }
    }
}