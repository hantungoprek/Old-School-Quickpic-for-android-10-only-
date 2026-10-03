package com.example.quickpic.ui.main

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.quickpic.NormalizedCropRect
import com.example.quickpic.ThumbnailCache
import com.example.quickpic.cropMediaFilePermanently
import com.example.quickpic.readDisplayedImageGeometry
import kotlinx.coroutines.launch

private fun Context.findActivityForCrop(): Activity? {
    var c: Context = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT, MOVE }

private val ASPECT_OPTIONS: List<Pair<String, Float?>> = listOf(
    "Bebas" to null,
    "1:1" to 1f,
    "4:3" to 4f / 3f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f,
)

private const val MIN_CROP_SIZE_PX = 64f
private const val HANDLE_TOUCH_RADIUS_DP = 24f

/**
 * Editor Crop foto — dibuka dari menu "Ubah" di overflow [MediaViewer].
 *
 * Sistem koordinat: [NormalizedCropRect] selalu 0f..1f relatif terhadap bitmap SUMBER
 * setelah [previewRotationDegrees] diterapkan (BUKAN koordinat layar). Gesture drag/pinch
 * di layar dikonversi ke ruang ini lewat [computeFitRect]/[computeEffectiveRect], sehingga
 * hasil crop akhir tetap akurat terlepas dari zoom, pan, letterboxing, atau rotasi preview.
 */
@Composable
internal fun CropScreen(
    item: com.example.quickpic.data.MediaItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbnailCache = remember { ThumbnailCache.getInstance(context) }
    val activity = context.findActivityForCrop()
    val density = LocalDensity.current

    // PENTING: pakai readDisplayedImageGeometry (bukan BitmapFactory bounds mentah) karena
    // Coil/AsyncImage otomatis menampilkan foto sudah terkoreksi EXIF-nya — untuk foto hasil
    // kamera HP yang piksel mentahnya landscape + tag EXIF orientation 90/270, ukuran mentah
    // akan salah (tertukar width/height) dibanding apa yang benar-benar dilihat user di layar.
    var srcSize by remember(item.uri) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(item.uri) {
        srcSize = context.readDisplayedImageGeometry(item.uri)?.let { IntSize(it.displayedWidth, it.displayedHeight) }
    }

    var previewRotationDegrees by remember(item.uri) { mutableIntStateOf(0) }
    // Default: margin wajar 10% di tiap sisi, mencakup sebagian besar foto.
    var cropRect by remember(item.uri) { mutableStateOf(NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f)) }
    var photoScale by remember(item.uri) { mutableFloatStateOf(1f) }
    var photoOffset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var selectedAspectRatio by remember(item.uri) { mutableStateOf<Float?>(null) }
    var aspectSheetOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                // --- Top App Bar ---
                Surface(color = Color.Black) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Kembali", tint = Color.White) }
                        Text("Ubah", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(
                            enabled = srcSize != null && !busy,
                            onClick = {
                                if (srcSize == null || busy) return@IconButton
                                busy = true
                                scope.launch {
                                    val result = context.cropMediaFilePermanently(item.uri, cropRect, previewRotationDegrees, thumbnailCache)
                                    busy = false
                                    if (result.success) onConfirm() else errorMessage = result.errorMessage ?: "Gagal menyimpan hasil crop."
                                }
                            },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Check, "Konfirmasi", tint = Color.White)
                            }
                        }
                        IconButton(
                            enabled = srcSize != null && !busy,
                            onClick = {
                                // Rotasi preview 90 derajat searah jarum jam; remap crop rect supaya
                                // area yang tadi dipilih tetap "menempel" pada objek yang sama.
                                val old = cropRect
                                cropRect = NormalizedCropRect(
                                    left = old.top,
                                    top = 1f - old.right,
                                    right = old.bottom,
                                    bottom = 1f - old.left,
                                )
                                previewRotationDegrees = (previewRotationDegrees + 90) % 360
                                selectedAspectRatio = selectedAspectRatio?.let { 1f / it }
                                photoScale = 1f
                                photoOffset = Offset.Zero
                            },
                        ) { Icon(Icons.Default.RotateRight, "Putar", tint = Color.White) }
                        IconButton(enabled = srcSize != null && !busy, onClick = { aspectSheetOpen = true }) {
                            Icon(Icons.Default.AspectRatio, "Rasio aspek", tint = Color.White)
                        }
                    }
                }

                // --- Area foto + crop overlay ---
                val handleTouchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { boxSizePx = it }
                        .pointerInput(item.uri, previewRotationDegrees) {
                            handleCropGestures(
                                boxSizeProvider = { boxSizePx },
                                sourceSizeProvider = { srcSize },
                                rotationDegreesProvider = { previewRotationDegrees },
                                photoScaleProvider = { photoScale },
                                photoOffsetProvider = { photoOffset },
                                cropRectProvider = { cropRect },
                                handleTouchRadiusPx = handleTouchRadiusPx,
                                aspectRatioProvider = { selectedAspectRatio },
                                onPhotoTransform = { scale, offset -> photoScale = scale; photoOffset = offset },
                                onCropRectChange = { cropRect = it },
                            )
                        },
                ) {
                    val currentSrcSize = srcSize
                    if (currentSrcSize == null) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
                    } else {
                        val effW = if (previewRotationDegrees % 180 == 0) currentSrcSize.width else currentSrcSize.height
                        val effH = if (previewRotationDegrees % 180 == 0) currentSrcSize.height else currentSrcSize.width
                        val boxSizeSnapshot = boxSizePx

                        if (boxSizeSnapshot.width > 0 && boxSizeSnapshot.height > 0) {
                            // Target footprint di layar (memakai dimensi EFEKTIF yang sudah
                            // mempertimbangkan swap akibat rotasi preview) — inilah yang dipakai
                            // overlay Canvas di bawah untuk menghitung posisi crop rect.
                            val targetFitRect = computeFitRect(boxSizeSnapshot, effW, effH)
                            // AsyncImage sendiri selalu men-Fit dimensi NATIVE (belum ditukar)
                            // lalu diputar visual lewat rotationZ. Supaya footprint hasil rotasi
                            // itu PERSIS berimpit dengan targetFitRect (bukan cuma "diputar di
                            // tempat" yang bisa beda ukuran untuk box non-persegi), ukuran
                            // pembungkusnya di-set ke targetFitRect yang DITUKAR width<->height
                            // saat rotasi 90/270 — bitmap native yang di-Fit lalu diputar 90°
                            // akan punya lebar hasil == tinggi kontainer aslinya, dan sebaliknya.
                            val innerWidthPx = if (previewRotationDegrees % 180 == 0) targetFitRect.width else targetFitRect.height
                            val innerHeightPx = if (previewRotationDegrees % 180 == 0) targetFitRect.height else targetFitRect.width
                            val innerWidthDp = with(density) { innerWidthPx.toDp() }
                            val innerHeightDp = with(density) { innerHeightPx.toDp() }

                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(item.uri).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(width = innerWidthDp, height = innerHeightDp)
                                        .graphicsLayer {
                                            scaleX = photoScale
                                            scaleY = photoScale
                                            translationX = photoOffset.x
                                            translationY = photoOffset.y
                                            rotationZ = previewRotationDegrees.toFloat()
                                        },
                                )
                            }
                        }

                        val cropRectSnapshot = cropRect
                        val photoScaleSnapshot = photoScale
                        val photoOffsetSnapshot = photoOffset

                        Canvas(Modifier.fillMaxSize()) {
                            if (boxSizeSnapshot.width == 0 || boxSizeSnapshot.height == 0) return@Canvas
                            val baseRect = computeFitRect(boxSizeSnapshot, effW, effH)
                            val boxCenter = Offset(boxSizeSnapshot.width / 2f, boxSizeSnapshot.height / 2f)
                            val effRect = computeEffectiveRect(baseRect, boxCenter, photoScaleSnapshot, photoOffsetSnapshot)

                            val cropLeft = effRect.left + cropRectSnapshot.left * effRect.width
                            val cropTop = effRect.top + cropRectSnapshot.top * effRect.height
                            val cropRight = effRect.left + cropRectSnapshot.right * effRect.width
                            val cropBottom = effRect.top + cropRectSnapshot.bottom * effRect.height

                            val canvasW = size.width
                            val canvasH = size.height
                            val scrimColor = Color.Black.copy(alpha = 0.6f)
                            // Scrim di luar area crop (4 strip: atas, bawah, kiri-tengah, kanan-tengah)
                            drawRect(scrimColor, topLeft = Offset(0f, 0f), size = Size(canvasW, cropTop.coerceAtLeast(0f)))
                            drawRect(scrimColor, topLeft = Offset(0f, cropBottom), size = Size(canvasW, (canvasH - cropBottom).coerceAtLeast(0f)))
                            drawRect(scrimColor, topLeft = Offset(0f, cropTop), size = Size(cropLeft.coerceAtLeast(0f), (cropBottom - cropTop).coerceAtLeast(0f)))
                            drawRect(scrimColor, topLeft = Offset(cropRight, cropTop), size = Size((canvasW - cropRight).coerceAtLeast(0f), (cropBottom - cropTop).coerceAtLeast(0f)))

                            // Border putih
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(cropLeft, cropTop),
                                size = Size(cropRight - cropLeft, cropBottom - cropTop),
                                style = Stroke(width = 2.dp.toPx()),
                            )

                            // Grid 3x3 (2 garis vertikal, 2 garis horizontal)
                            val gridColor = Color.White.copy(alpha = 0.6f)
                            val thirdW = (cropRight - cropLeft) / 3f
                            val thirdH = (cropBottom - cropTop) / 3f
                            for (i in 1..2) {
                                drawLine(gridColor, Offset(cropLeft + thirdW * i, cropTop), Offset(cropLeft + thirdW * i, cropBottom), strokeWidth = 1.dp.toPx())
                                drawLine(gridColor, Offset(cropLeft, cropTop + thirdH * i), Offset(cropRight, cropTop + thirdH * i), strokeWidth = 1.dp.toPx())
                            }

                            // Handle diamond di 4 sudut + 4 titik tengah sisi
                            val handleSize = 10.dp.toPx()
                            val handlePoints = listOf(
                                Offset(cropLeft, cropTop), Offset(cropRight, cropTop), Offset(cropLeft, cropBottom), Offset(cropRight, cropBottom),
                                Offset((cropLeft + cropRight) / 2f, cropTop), Offset((cropLeft + cropRight) / 2f, cropBottom),
                                Offset(cropLeft, (cropTop + cropBottom) / 2f), Offset(cropRight, (cropTop + cropBottom) / 2f),
                            )
                            handlePoints.forEach { p ->
                                val path = Path().apply {
                                    moveTo(p.x, p.y - handleSize)
                                    lineTo(p.x + handleSize, p.y)
                                    lineTo(p.x, p.y + handleSize)
                                    lineTo(p.x - handleSize, p.y)
                                    close()
                                }
                                drawPath(path, Color.White)
                            }
                        }
                    }
                }
            }

            errorMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text("Gagal menyimpan") },
                    text = { Text(msg) },
                    confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
                )
            }

            if (aspectSheetOpen) {
                AlertDialog(
                    onDismissRequest = { aspectSheetOpen = false },
                    title = { Text("Rasio Aspek") },
                    text = {
                        Column {
                            ASPECT_OPTIONS.forEach { (label, ratio) ->
                                TextButton(onClick = {
                                    selectedAspectRatio = ratio
                                    aspectSheetOpen = false
                                    val currentSrcSize = srcSize
                                    if (ratio != null && currentSrcSize != null) {
                                        val effW = if (previewRotationDegrees % 180 == 0) currentSrcSize.width else currentSrcSize.height
                                        val effH = if (previewRotationDegrees % 180 == 0) currentSrcSize.height else currentSrcSize.width
                                        cropRect = fitAspectRatioToCenter(cropRect, ratio, effW, effH)
                                    }
                                }) { Text(label) }
                            }
                        }
                    },
                    confirmButton = {},
                )
            }
        }
    }
}

/** Loop gesture tunggal: 1 jari = drag handle/geser crop rect, 2 jari = pinch-zoom & pan foto. */
private suspend fun PointerInputScope.handleCropGestures(
    boxSizeProvider: () -> IntSize,
    sourceSizeProvider: () -> IntSize?,
    rotationDegreesProvider: () -> Int,
    photoScaleProvider: () -> Float,
    photoOffsetProvider: () -> Offset,
    cropRectProvider: () -> NormalizedCropRect,
    handleTouchRadiusPx: Float,
    aspectRatioProvider: () -> Float?,
    onPhotoTransform: (Float, Offset) -> Unit,
    onCropRectChange: (NormalizedCropRect) -> Unit,
) {
    awaitEachGesture {
        val size = sourceSizeProvider() ?: return@awaitEachGesture
        val box = boxSizeProvider()
        if (box.width == 0 || box.height == 0) return@awaitEachGesture
        val degrees = rotationDegreesProvider()
        val effW = if (degrees % 180 == 0) size.width else size.height
        val effH = if (degrees % 180 == 0) size.height else size.width
        val minWNorm = (MIN_CROP_SIZE_PX / effW).coerceAtMost(0.9f)
        val minHNorm = (MIN_CROP_SIZE_PX / effH).coerceAtMost(0.9f)

        val down = awaitFirstDown(requireUnconsumed = false)
        val baseRect = computeFitRect(box, effW, effH)
        val boxCenter = Offset(box.width / 2f, box.height / 2f)
        val effectiveRectAtDown = computeEffectiveRect(baseRect, boxCenter, photoScaleProvider(), photoOffsetProvider())
        var activeHandle: CropHandle? = hitTestHandle(down.position, effectiveRectAtDown, cropRectProvider(), handleTouchRadiusPx)

        do {
            val event = awaitPointerEvent()
            val isMultiTouch = event.changes.size > 1
            if (isMultiTouch) {
                activeHandle = null
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val newScale = (photoScaleProvider() * zoomChange).coerceIn(1f, 5f)
                onPhotoTransform(newScale, photoOffsetProvider() + panChange)
                event.changes.forEach { c -> if (c.positionChanged()) c.consume() }
            } else if (activeHandle != null) {
                val change: PointerInputChange = event.changes.first()
                if (change.positionChanged()) {
                    val curEffectiveRect = computeEffectiveRect(baseRect, boxCenter, photoScaleProvider(), photoOffsetProvider())
                    if (curEffectiveRect.width > 0f && curEffectiveRect.height > 0f) {
                        val dx = change.positionChange().x / curEffectiveRect.width
                        val dy = change.positionChange().y / curEffectiveRect.height
                        val updated = applyHandleDelta(cropRectProvider(), activeHandle!!, dx, dy, minWNorm, minHNorm, aspectRatioProvider(), effW, effH)
                        onCropRectChange(updated)
                    }
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Rect (dalam px Box) tempat bitmap sumber di-render dengan ContentScale.Fit, pada skala 1x tanpa pan. */
private fun computeFitRect(boxSize: IntSize, contentWidth: Int, contentHeight: Int): Rect {
    if (contentWidth <= 0 || contentHeight <= 0 || boxSize.width <= 0 || boxSize.height <= 0) return Rect.Zero
    val boxAspect = boxSize.width.toFloat() / boxSize.height
    val contentAspect = contentWidth.toFloat() / contentHeight
    return if (contentAspect > boxAspect) {
        val displayWidth = boxSize.width.toFloat()
        val displayHeight = displayWidth / contentAspect
        val top = (boxSize.height - displayHeight) / 2f
        Rect(0f, top, displayWidth, top + displayHeight)
    } else {
        val displayHeight = boxSize.height.toFloat()
        val displayWidth = displayHeight * contentAspect
        val left = (boxSize.width - displayWidth) / 2f
        Rect(left, 0f, left + displayWidth, displayHeight)
    }
}

/** Rect bitmap sumber setelah scale+pan (pivot di tengah Box, sesuai default graphicsLayer). */
private fun computeEffectiveRect(baseRect: Rect, boxCenter: Offset, scale: Float, offset: Offset): Rect {
    if (baseRect == Rect.Zero) return baseRect
    val left = boxCenter.x + (baseRect.left - boxCenter.x) * scale + offset.x
    val top = boxCenter.y + (baseRect.top - boxCenter.y) * scale + offset.y
    val right = boxCenter.x + (baseRect.right - boxCenter.x) * scale + offset.x
    val bottom = boxCenter.y + (baseRect.bottom - boxCenter.y) * scale + offset.y
    return Rect(left, top, right, bottom)
}

private fun hitTestHandle(pos: Offset, effectiveRect: Rect, cropRect: NormalizedCropRect, touchRadiusPx: Float): CropHandle? {
    if (effectiveRect.width <= 0f || effectiveRect.height <= 0f) return null
    val left = effectiveRect.left + cropRect.left * effectiveRect.width
    val top = effectiveRect.top + cropRect.top * effectiveRect.height
    val right = effectiveRect.left + cropRect.right * effectiveRect.width
    val bottom = effectiveRect.top + cropRect.bottom * effectiveRect.height
    val midX = (left + right) / 2f
    val midY = (top + bottom) / 2f

    val candidates = listOf(
        CropHandle.TOP_LEFT to Offset(left, top),
        CropHandle.TOP_RIGHT to Offset(right, top),
        CropHandle.BOTTOM_LEFT to Offset(left, bottom),
        CropHandle.BOTTOM_RIGHT to Offset(right, bottom),
        CropHandle.TOP to Offset(midX, top),
        CropHandle.BOTTOM to Offset(midX, bottom),
        CropHandle.LEFT to Offset(left, midY),
        CropHandle.RIGHT to Offset(right, midY),
    )
    val nearest = candidates.minByOrNull { (pos - it.second).getDistance() }
    if (nearest != null && (pos - nearest.second).getDistance() <= touchRadiusPx) return nearest.first
    if (pos.x in left..right && pos.y in top..bottom) return CropHandle.MOVE
    return null
}

private fun applyHandleDelta(
    rect: NormalizedCropRect,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minW: Float,
    minH: Float,
    aspect: Float?,
    effW: Int,
    effH: Int,
): NormalizedCropRect {
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    when (handle) {
        CropHandle.MOVE -> {
            val w = right - left
            val h = bottom - top
            left = (left + dx).coerceIn(0f, 1f - w)
            top = (top + dy).coerceIn(0f, 1f - h)
            right = left + w
            bottom = top + h
        }
        CropHandle.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minW)
            top = (top + dy).coerceIn(0f, bottom - minH)
        }
        CropHandle.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, 1f)
            top = (top + dy).coerceIn(0f, bottom - minH)
        }
        CropHandle.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minW)
            bottom = (bottom + dy).coerceIn(top + minH, 1f)
        }
        CropHandle.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, 1f)
            bottom = (bottom + dy).coerceIn(top + minH, 1f)
        }
        CropHandle.TOP -> top = (top + dy).coerceIn(0f, bottom - minH)
        CropHandle.BOTTOM -> bottom = (bottom + dy).coerceIn(top + minH, 1f)
        CropHandle.LEFT -> left = (left + dx).coerceIn(0f, right - minW)
        CropHandle.RIGHT -> right = (right + dx).coerceIn(left + minW, 1f)
    }
    var result = NormalizedCropRect(left, top, right, bottom).coerceValid()
    if (aspect != null && handle != CropHandle.MOVE && effW > 0 && effH > 0) {
        result = fitAspectRatioFromHandle(result, handle, aspect, minH, effW, effH)
    }
    return result
}

/** Sesuaikan sisi yang tidak sedang di-drag supaya rasio piksel (effW/effH) tetap terjaga. */
private fun fitAspectRatioFromHandle(
    rect: NormalizedCropRect,
    handle: CropHandle,
    aspect: Float,
    minH: Float,
    effW: Int,
    effH: Int,
): NormalizedCropRect {
    val widthNorm = rect.width
    val targetHeightNorm = (widthNorm * effW / aspect / effH).coerceAtLeast(minH)
    var top = rect.top
    var bottom = rect.bottom
    when (handle) {
        CropHandle.TOP_LEFT, CropHandle.TOP, CropHandle.TOP_RIGHT -> top = (bottom - targetHeightNorm).coerceAtLeast(0f)
        else -> bottom = (top + targetHeightNorm).coerceAtMost(1f)
    }
    return NormalizedCropRect(rect.left, top, rect.right, bottom).coerceValid()
}

/** Terapkan rasio aspek baru dari tengah crop rect saat ini (dipakai saat user memilih dari menu Rasio Aspek). */
private fun fitAspectRatioToCenter(rect: NormalizedCropRect, aspect: Float, effW: Int, effH: Int): NormalizedCropRect {
    if (effW <= 0 || effH <= 0) return rect
    val centerX = (rect.left + rect.right) / 2f
    val centerY = (rect.top + rect.bottom) / 2f
    val widthNorm = rect.width
    val heightNorm = widthNorm * effW / aspect / effH
    var left = centerX - widthNorm / 2f
    var right = centerX + widthNorm / 2f
    var top = centerY - heightNorm / 2f
    var bottom = centerY + heightNorm / 2f
    // Geser ke dalam batas 0..1 kalau kepentok tepi, tanpa mengubah ukurannya.
    if (left < 0f) { right -= left; left = 0f }
    if (right > 1f) { left -= (right - 1f); right = 1f }
    if (top < 0f) { bottom -= top; top = 0f }
    if (bottom > 1f) { top -= (bottom - 1f); bottom = 1f }
    return NormalizedCropRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
}
