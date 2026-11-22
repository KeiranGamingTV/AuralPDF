package com.keiranhaas.auralpdf.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.text.Text as MlText
import com.keiranhaas.auralpdf.data.*
import com.keiranhaas.auralpdf.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Stage { CAMERA, EDIT, REVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { JobState() }
    val pages = remember { mutableStateListOf<File>() }
    val incoming = remember { mutableStateListOf<File>() }
    val corners = remember { mutableStateListOf<Offset>() }
    val ocrCache = remember { HashMap<File, MlText>() }
    var stage by remember { mutableStateOf(Stage.CAMERA) }
    var editBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var filter by remember { mutableStateOf(ScanFilter.ORIGINAL) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var working by remember { mutableStateOf(false) }
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) camPerm.launch(Manifest.permission.CAMERA) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            working = true
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { ScanStore.importImage(ctx, it) }.getOrNull() } }
            incoming.addAll(files)
            working = false
            if (incoming.isNotEmpty()) stage = Stage.EDIT
        }
    }

    LaunchedEffect(stage, incoming.firstOrNull()) {
        val f = incoming.firstOrNull()
        if (stage == Stage.EDIT && f != null) {
            editBitmap = null
            val (bmp, pts) = withContext(Dispatchers.Default) { val b = ScanStore.load(f); b to DocScanner.detect(b) }
            corners.clear(); corners.addAll(pts.map { Offset(it.x, it.y) })
            editBitmap = bmp
        }
    }

    fun discardCurrent() {
        incoming.firstOrNull()?.delete()
        if (incoming.isNotEmpty()) incoming.removeAt(0)
        stage = if (incoming.isEmpty()) Stage.CAMERA else Stage.EDIT
    }
    BackHandler(enabled = stage == Stage.EDIT) { discardCurrent() }
    BackHandler(enabled = stage == Stage.REVIEW) { stage = Stage.CAMERA }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (stage) { Stage.CAMERA -> "Scan"; Stage.EDIT -> "Adjust edges"; Stage.REVIEW -> "Review (${pages.size})" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        when (stage) { Stage.CAMERA -> onBack(); Stage.EDIT -> discardCurrent(); Stage.REVIEW -> stage = Stage.CAMERA }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (stage) {
                Stage.CAMERA -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (hasCam) {
                            CameraPreview(Modifier.fillMaxSize()) { capture = it }
                        } else {
                            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Camera permission is needed to scan. You can still import photos.")
                                Button(onClick = { camPerm.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { importer.launch("image/*") }) { Text("Import") }
                        Button(
                            enabled = capture != null && !working,
                            modifier = Modifier.size(72.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp),
                            onClick = {
                                working = true
                                capture!!.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val file = runCatching { ScanStore.saveCapture(ctx, image) }.getOrNull()
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            working = false
                                            if (file != null) { incoming.add(file); stage = Stage.EDIT }
                                        }
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        ContextCompat.getMainExecutor(ctx).execute { working = false }
                                    }
                                })
                            }
                        ) {}
                        FilledTonalButton(enabled = pages.isNotEmpty(), onClick = { stage = Stage.REVIEW }) { Text("Done (${pages.size})") }
                    }
                }

                Stage.EDIT -> Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val bmp = editBitmap
                    if (bmp == null) Spacer(Modifier.weight(1f)) else CropEditor(bmp, corners, Modifier.weight(1f).fillMaxWidth())
                    Text("Drag the corner handles to match the page edges.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScanFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { discardCurrent() }, modifier = Modifier.weight(1f)) { Text("Discard") }
                        Button(
                            enabled = bmp != null && !working, modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    working = true
                                    val cur = incoming.firstOrNull()
                                    val saved = withContext(Dispatchers.Default) {
                                        val warped = DocScanner.warp(bmp!!, corners.map { PointF(it.x, it.y) })
                                        val result = DocScanner.filter(warped, filter)
                                        ScanStore.newFile(ctx, "page").also { ScanStore.saveJpeg(result, it, 90) }
                                    }
                                    pages += saved
                                    cur?.delete()
                                    if (incoming.isNotEmpty()) incoming.removeAt(0)
                                    working = false
                                    stage = if (incoming.isEmpty()) Stage.CAMERA else Stage.EDIT
                                }
                            }
                        ) { Text("Accept") }
                    }
                }

                Stage.REVIEW -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pages.forEachIndexed { i, f ->
                        val thumb = remember(f) { ScanStore.thumb(f) }
                        Card {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                thumb?.let { Image(it.asImageBitmap(), null, Modifier.height(96.dp)) }
                                Text("Page ${i + 1}", Modifier.weight(1f))
                                IconButton(onClick = {
                                    f.delete(); ocrCache.remove(f); pages.removeAt(i)
                                    if (pages.isEmpty()) stage = Stage.CAMERA
                                }) { Icon(Icons.Default.Delete, "Remove page") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { stage = Stage.CAMERA }) { Text("Add page") }
                    Button(enabled = pages.isNotEmpty() && !job.busy, onClick = {
                        val snapshot = pages.toList()
                        job.launch(scope) {
                            val texts = ocrPages(snapshot, ocrCache)
                            status = "Building PDF…"
                            val name = "scan_${timestamp()}.pdf"
                            val out = OutputFiles.create(ctx, name)
                            PdfOps.buildScanPdf(snapshot.mapIndexed { i, f ->
                                val (w, h) = ScanStore.size(f)
                                ScannedPage(f, w, h, texts[i])
                            }, out)
                            OutFile(out, "application/pdf", name, "Text is searchable and selectable in most PDF viewers.")
                        }
                    }) { Text("Save as searchable PDF") }
                    OutlinedButton(enabled = pages.isNotEmpty() && !job.busy, onClick = {
                        val snapshot = pages.toList()
                        job.launch(scope) {
                            val texts = ocrPages(snapshot, ocrCache)
                            val body = texts.mapIndexed { i, t -> "--- Page ${i + 1} ---\n${t.text}" }.joinToString("\n\n")
                            val name = "scan_${timestamp()}.txt"
                            val out = OutputFiles.create(ctx, name)
                            out.writeText(body)
                            OutFile(out, "text/plain", name)
                        }
                    }) { Text("Extract text") }
                    JobPanel(job)
                }
            }
            if (working) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

private suspend fun JobState.ocrPages(pages: List<File>, cache: MutableMap<File, MlText>): List<MlText> =
    pages.mapIndexed { i, f ->
        status = "Reading text ${i + 1}/${pages.size}"
        cache.getOrPut(f) {
            val b = ScanStore.load(f)
            try { OcrEngine.recognize(b) } finally { b.recycle() }
        }
    }

@Composable
private fun CameraPreview(modifier: Modifier, onReady: (ImageCapture) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(modifier = modifier, factory = { c ->
        val previewView = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            onReady(imageCapture)
        }, ContextCompat.getMainExecutor(c))
        previewView
    })
}

/** Shows [bitmap] with a draggable quad. [corners] are in bitmap pixel coordinates (TL, TR, BR, BL). */
@Composable
private fun CropEditor(bitmap: Bitmap, corners: SnapshotStateList<Offset>, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = if (size == IntSize.Zero) 1f else min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
    val ox = (size.width - bitmap.width * scale) / 2f
    val oy = (size.height - bitmap.height * scale) / 2f
    var active by remember { mutableIntStateOf(-1) }
    val color = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier.onSizeChanged { size = it }.pointerInput(bitmap, size) {
            detectDragGestures(
                onDragStart = { p ->
                    var best = -1
                    var bestDist = 90f
                    corners.forEachIndexed { i, c ->
                        val d = (Offset(c.x * scale + ox, c.y * scale + oy) - p).getDistance()
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    active = best
                },
                onDrag = { change, drag ->
                    if (active >= 0) {
                        change.consume()
                        val c = corners[active]
                        corners[active] = Offset(
                            (c.x + drag.x / scale).coerceIn(0f, bitmap.width.toFloat()),
                            (c.y + drag.y / scale).coerceIn(0f, bitmap.height.toFloat())
                        )
                    }
                },
                onDragEnd = { active = -1 },
                onDragCancel = { active = -1 }
            )
        }
    ) {
        drawImage(
            image,
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        )
        if (corners.size == 4) {
            val pts = corners.map { Offset(it.x * scale + ox, it.y * scale + oy) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color, style = Stroke(width = 4f))
            pts.forEach { drawCircle(color, radius = 18f, center = it) }
        }
    }
}