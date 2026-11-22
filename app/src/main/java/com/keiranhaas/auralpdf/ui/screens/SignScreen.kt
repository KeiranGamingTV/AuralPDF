package com.keiranhaas.auralpdf.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SignScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    var src by remember { mutableStateOf<File?>(null) }
    var renderer by remember { mutableStateOf<PdfPageRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pageBmp by remember { mutableStateOf<Bitmap?>(null) }
    var signature by remember { mutableStateOf(SignatureStore.load(ctx)) }
    var useText by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var widthFrac by remember { mutableFloatStateOf(0.3f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.8f) }
    var showPad by remember { mutableStateOf(false) }
    val stamps = remember { mutableStateListOf<Stamp>() }
    val textBmp = remember(text) { if (text.isBlank()) null else StampBitmaps.text(text) }
    val draft = if (useText) textBmp else signature

    DisposableEffect(Unit) { onDispose { renderer?.close() } }
    LaunchedEffect(renderer, page) {
        val r = renderer ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) { runCatching { r.render(page, 1200) }.getOrNull() }
        if (bmp != null) pageBmp = bmp
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.launch(scope) {
            status = "Opening…"
            val f = ctx.importToCache(uri)
            val r = PdfPageRenderer(f)
            renderer?.close()
            stamps.clear(); page = 0; pageCount = r.pageCount; src = f; renderer = r
            null
        }
    }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { StampBitmaps.fromPhoto(ctx, uri) }.getOrNull() }
            if (b != null) { signature = b; SignatureStore.save(ctx, b) }
        }
    }

    if (showPad) {
        SignaturePadDialog(
            onDismiss = { showPad = false },
            onDone = { b -> signature = b; SignatureStore.save(ctx, b); showPad = false }
        )
    }

    ToolScaffold("Sign & annotate", onBack) {
        OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) { Text(if (src == null) "Choose a PDF" else "Choose a different PDF") }

        if (src != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useText, onClick = { useText = false }, label = { Text("Signature") })
                FilterChip(selected = useText, onClick = { useText = true }, label = { Text("Text / date") })
            }
            if (!useText) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { showPad = true }) { Text("Draw") }
                    OutlinedButton(onClick = { imgPicker.launch("image/*") }) { Text("Import image") }
                    if (signature != null) TextButton(onClick = { SignatureStore.delete(ctx); signature = null }) { Text("Forget") }
                }
                if (signature == null) Text("Draw or import a signature. It's stored only on this device.", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Text to place") })
                TextButton(onClick = {
                    val d = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())
                    text = if (text.isBlank()) d else "$text $d"
                }) { Text("Insert today's date") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                Text("Page ${page + 1} of $pageCount", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(enabled = page < pageCount - 1, onClick = { page++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
            }

            pageBmp?.let { bmp ->
                var box by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .onSizeChanged { box = it }
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                cx = (p.x / size.width).coerceIn(0f, 1f); cy = (p.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                cx = (change.position.x / size.width).coerceIn(0f, 1f)
                                cy = (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
                    stamps.filter { it.page == page }.forEach { StampOverlay(it.bitmap, it.cx, it.cy, it.widthFrac, box, 1f, false) }
                    draft?.let { StampOverlay(it, cx, cy, widthFrac, box, 0.85f, true) }
                }
                Text("Tap or drag on the page to position it.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Size", style = MaterialTheme.typography.labelMedium)
            Slider(value = widthFrac, onValueChange = { widthFrac = it }, valueRange = 0.05f..0.7f)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = draft != null, onClick = { stamps += Stamp(page, cx, cy, widthFrac, draft!!) }) { Text("Place on page ${page + 1}") }
                TextButton(enabled = stamps.isNotEmpty(), onClick = { stamps.removeAt(stamps.lastIndex) }) { Text("Undo") }
            }
            Button(enabled = stamps.isNotEmpty() && !job.busy, onClick = {
                val file = src!!
                val all = stamps.toList()
                job.launch(scope) {
                    status = "Applying…"
                    val name = "${file.nameWithoutExtension}_signed.pdf"
                    val out = OutputFiles.create(ctx, name)
                    PdfOps.applyStamps(file, out, all)
                    OutFile(out, "application/pdf", name, "${all.size} item(s) added. This is a visual signature, not a certificate-based one.")
                }
            }) { Text("Save PDF (${stamps.size} placed)") }
        }
        JobPanel(job)
    }
}

@Composable
private fun StampOverlay(bmp: Bitmap, cx: Float, cy: Float, wf: Float, box: IntSize, alpha: Float, outlined: Boolean) {
    if (box.width == 0) return
    val density = LocalDensity.current
    val wPx = wf * box.width
    val hPx = wPx * bmp.height / bmp.width
    Image(
        bmp.asImageBitmap(), null,
        Modifier
            .offset { IntOffset((cx * box.width - wPx / 2).roundToInt(), (cy * box.height - hPx / 2).roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .alpha(alpha)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.primary) else Modifier)
    )
}

@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<SnapshotStateList<Offset>>() }
    val ink = Color(0xFF0B2A6F)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw your signature") },
        text = {
            Canvas(
                Modifier.fillMaxWidth().height(200.dp).background(Color.White).border(1.dp, Color.Gray)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { strokes.add(mutableStateListOf(it)) },
                            onDrag = { change, _ -> change.consume(); strokes.lastOrNull()?.add(change.position) }
                        )
                    }
            ) {
                strokes.forEach { s ->
                    val path = Path()
                    s.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    drawPath(path, ink, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                StampBitmaps.fromStrokes(strokes.map { it.toList() })?.let(onDone)
            }) { Text("Use") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}