package com.lantaservice.intra.ui.camera

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.ui.components.intraHeaderInsetsPadding
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.CameraReviewPair
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.FrameQualityAnalyzer
import com.lantaservice.intra.util.FrameQualityMetrics
import com.lantaservice.intra.util.ImageCompressor
import com.lantaservice.intra.util.LightStatus
import com.lantaservice.intra.util.SharpnessStatus
import com.lantaservice.intra.util.StabilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Full-screen smart CameraX viewfinder with real-time hints for lighting, focus,
 * and hand stability, plus built-in text clarity enhancement review.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun SmartCameraScreen(
    taskId: Int,
    photoIndex: Int,
    onDismiss: () -> Unit,
    onPhotoAccepted: (PendingPhoto) -> Unit,
    onCameraFallbackRequested: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var quality by remember { mutableStateOf(FrameQualityMetrics()) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var textEnhanceEnabled by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }
    var reviewPair by remember { mutableStateOf<CameraReviewPair?>(null) }
    var useEnhancedInReview by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            reviewPair?.let { pair ->
                runCatching { pair.enhancedPhoto.file.delete() }
                runCatching { pair.originalPhoto.file.delete() }
            }
        }
    }

    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            focusAlpha.snapTo(1f)
            focusAlpha.animateTo(0f, animationSpec = tween(900))
            focusTapOffset = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AnimatedContent(
                targetState = reviewPair,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
                label = "camera_review_switch"
            ) { pair ->
                if (pair == null) {
                    // Live CameraX Viewfinder
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(cameraRef, previewViewRef) {
                                    detectTapGestures { offset ->
                                        val cam = cameraRef ?: return@detectTapGestures
                                        val pv = previewViewRef ?: return@detectTapGestures
                                        focusTapOffset = offset
                                        val pt = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                                        val action = FocusMeteringAction.Builder(
                                            pt,
                                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                        )
                                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                            .build()
                                        cam.cameraControl.startFocusAndMetering(action)
                                    }
                                }
                                .pointerInput(cameraRef) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        val cam = cameraRef ?: return@detectTransformGestures
                                        val zs = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                                        val next = (zoomRatio * zoom).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                                        zoomRatio = next
                                        cam.cameraControl.setZoomRatio(next)
                                    }
                                },
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    val previewView = this
                                    previewViewRef = previewView

                                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                    providerFuture.addListener({
                                        runCatching {
                                            val provider = providerFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.surfaceProvider = previewView.surfaceProvider
                                            }
                                            val imageCapture = ImageCapture.Builder()
                                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                                .build()
                                            val imageAnalysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()
                                                .apply {
                                                    setAnalyzer(
                                                        analysisExecutor,
                                                        FrameQualityAnalyzer { m -> quality = m }
                                                    )
                                                }

                                            provider.unbindAll()
                                            val cam = provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                imageCapture,
                                                imageAnalysis
                                            )
                                            cameraRef = cam
                                            imageCaptureRef = imageCapture
                                            hasFlash = cam.cameraInfo.hasFlashUnit()
                                        }.onFailure {
                                            onCameraFallbackRequested()
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                }
                            }
                        )

                        // Tap-to-focus ring indicator
                        focusTapOffset?.let { tap ->
                            val dpSize = 68.dp
                            val halfPx = with(LocalDensity.current) { (dpSize / 2).toPx() }
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (tap.x - halfPx).roundToInt(),
                                            (tap.y - halfPx).roundToInt()
                                        )
                                    }
                                    .size(dpSize)
                                    .border(
                                        2.dp,
                                        IntraColors.BrandCyan.copy(alpha = focusAlpha.value),
                                        CircleShape
                                    )
                            )
                        }

                        // Alignment corner frame that changes color dynamically with frame readiness
                        val frameColor by animateColorAsState(
                            targetValue = when {
                                quality.isOptimalToShoot -> IntraColors.EmeraldSuccess
                                quality.light == LightStatus.TOO_DARK ||
                                    quality.stability == StabilityStatus.MOVING -> IntraColors.ErrorRed
                                else -> IntraColors.WarningAmber
                            },
                            label = "guide_frame_color"
                        )
                        Canvas(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.84f)
                                .height(280.dp)
                        ) {
                            val strokeW = 4.dp.toPx()
                            val corner = 28.dp.toPx()
                            val w = size.width
                            val h = size.height

                            // Top-left
                            drawLine(frameColor, Offset(0f, 0f), Offset(corner, 0f), strokeW, StrokeCap.Round)
                            drawLine(frameColor, Offset(0f, 0f), Offset(0f, corner), strokeW, StrokeCap.Round)
                            // Top-right
                            drawLine(frameColor, Offset(w, 0f), Offset(w - corner, 0f), strokeW, StrokeCap.Round)
                            drawLine(frameColor, Offset(w, 0f), Offset(w, corner), strokeW, StrokeCap.Round)
                            // Bottom-left
                            drawLine(frameColor, Offset(0f, h), Offset(corner, h), strokeW, StrokeCap.Round)
                            drawLine(frameColor, Offset(0f, h), Offset(0f, h - corner), strokeW, StrokeCap.Round)
                            // Bottom-right
                            drawLine(frameColor, Offset(w, h), Offset(w - corner, h), strokeW, StrokeCap.Round)
                            drawLine(frameColor, Offset(w, h), Offset(w, h - corner), strokeW, StrokeCap.Round)
                        }

                        // Top HUD: Close, title & Torch on first row, 3 live condition pills wrapped cleanly below
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .intraHeaderInsetsPadding()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = Color.Black.copy(alpha = 0.55f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                                }

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.Black.copy(alpha = 0.55f),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(
                                        text = "Фото для заявки #$taskId",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                if (hasFlash) {
                                    IconButton(
                                        onClick = {
                                            val next = !torchEnabled
                                            torchEnabled = next
                                            cameraRef?.cameraControl?.enableTorch(next)
                                        },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = if (torchEnabled) {
                                                IntraColors.WarningAmber
                                            } else {
                                                Color.Black.copy(alpha = 0.55f)
                                            },
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                            contentDescription = "Фонарик"
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(48.dp))
                                }
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ConditionPill(
                                    icon = Icons.Rounded.WbSunny,
                                    label = "Свет",
                                    state = when (quality.light) {
                                        LightStatus.OK -> PillState.GOOD
                                        LightStatus.GLARE -> PillState.WARN
                                        LightStatus.TOO_DARK -> PillState.BAD
                                    }
                                )
                                ConditionPill(
                                    icon = Icons.Rounded.CenterFocusStrong,
                                    label = "Чёткость",
                                    state = if (quality.sharpness == SharpnessStatus.OK) {
                                        PillState.GOOD
                                    } else {
                                        PillState.WARN
                                    }
                                )
                                ConditionPill(
                                    icon = Icons.Rounded.Vibration,
                                    label = "Стабильно",
                                    state = if (quality.stability == StabilityStatus.STEADY) {
                                        PillState.GOOD
                                    } else {
                                        PillState.BAD
                                    }
                                )
                            }

                            // Primary live hint pill ("когда лучше фоткать")
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.68f),
                                border = BorderStroke(1.dp, frameColor.copy(alpha = 0.7f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = quality.primaryHint,
                                        color = if (quality.isOptimalToShoot) Color(0xFF8FF5BE) else Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Держите текст ровно в рамке, без бликов (нажмите на экран для фокуса)",
                                        color = Color.White.copy(alpha = 0.75f),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Bottom shutter controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.58f))
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (textEnhanceEnabled) {
                                    IntraColors.EmeraldSuccess.copy(alpha = 0.25f)
                                } else {
                                    Color.White.copy(alpha = 0.14f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (textEnhanceEnabled) IntraColors.EmeraldSuccess else Color.White.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { textEnhanceEnabled = !textEnhanceEnabled }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoFixHigh,
                                        contentDescription = null,
                                        tint = if (textEnhanceEnabled) Color(0xFF8FF5BE) else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (textEnhanceEnabled) {
                                            "Усиление четкости текста: ВКЛ"
                                        } else {
                                            "Усиление четкости текста: ВЫКЛ"
                                        },
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .border(
                                        4.dp,
                                        if (quality.isOptimalToShoot) IntraColors.EmeraldSuccess else Color.White,
                                        CircleShape
                                    )
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isCapturing) Color.Gray else Color.White)
                                    .clickable(enabled = !isCapturing) {
                                        val capture = imageCaptureRef ?: return@clickable
                                        isCapturing = true
                                        val (rawFile, _) = ImageCompressor.createTempCameraFile(context, taskId)
                                        val outputOpts = ImageCapture.OutputFileOptions.Builder(rawFile).build()
                                        capture.takePicture(
                                            outputOpts,
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageSavedCallback {
                                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                    scope.launch {
                                                        runCatching {
                                                            ImageCompressor.prepareCameraReviewPair(
                                                                context = context,
                                                                rawFile = rawFile,
                                                                taskId = taskId,
                                                                index = photoIndex
                                                            )
                                                        }.onSuccess { prepared ->
                                                            useEnhancedInReview = textEnhanceEnabled
                                                            reviewPair = prepared
                                                            isCapturing = false
                                                        }.onFailure {
                                                            isCapturing = false
                                                        }
                                                    }
                                                }

                                                override fun onError(exception: ImageCaptureException) {
                                                    isCapturing = false
                                                    runCatching { rawFile.delete() }
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCapturing) {
                                    CircularProgressIndicator(
                                        color = IntraColors.BrandPrimary,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Quick Enhancement Review Screen
                    val activePhoto = if (useEnhancedInReview) pair.enhancedPhoto else pair.originalPhoto
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = activePhoto.file,
                            contentDescription = "Предпросмотр",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 140.dp, top = 76.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .intraHeaderInsetsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f, fill = false),
                                color = if (activePhoto.isBlurryWarning) {
                                    IntraColors.WarningAmber.copy(alpha = 0.9f)
                                } else {
                                    IntraColors.EmeraldSuccess.copy(alpha = 0.9f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (activePhoto.isBlurryWarning) {
                                            Icons.Rounded.WarningAmber
                                        } else {
                                            Icons.Rounded.CheckCircle
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (activePhoto.isBlurryWarning) {
                                            "Возможна нечёткость - лучше переснять"
                                        } else {
                                            "Текст чёткий (${DateAndTextFormatter.formatBytes(activePhoto.sizeBytes)})"
                                        },
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.6f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.8f))
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReviewModeChip(
                                    label = "Улучшено (резкий текст)",
                                    selected = useEnhancedInReview,
                                    onClick = { useEnhancedInReview = true },
                                    modifier = Modifier.weight(1f)
                                )
                                ReviewModeChip(
                                    label = "Оригинал",
                                    selected = !useEnhancedInReview,
                                    onClick = { useEnhancedInReview = false },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { pair.enhancedPhoto.file.delete() }
                                            runCatching { pair.originalPhoto.file.delete() }
                                            withContext(Dispatchers.Main) {
                                                reviewPair = null
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Переснять", color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        val chosen = if (useEnhancedInReview) pair.enhancedPhoto else pair.originalPhoto
                                        val unused = if (useEnhancedInReview) pair.originalPhoto else pair.enhancedPhoto
                                        runCatching { unused.file.delete() }
                                        reviewPair = null
                                        onPhotoAccepted(chosen)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = IntraColors.EmeraldSuccess,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Прикрепить", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PillState {
    GOOD,
    WARN,
    BAD
}

@Composable
private fun ConditionPill(
    icon: ImageVector,
    label: String,
    state: PillState
) {
    val bg = when (state) {
        PillState.GOOD -> IntraColors.EmeraldSuccess.copy(alpha = 0.78f)
        PillState.WARN -> IntraColors.WarningAmber.copy(alpha = 0.82f)
        PillState.BAD -> IntraColors.ErrorRed.copy(alpha = 0.85f)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ReviewModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) IntraColors.BrandPrimary else Color.White.copy(alpha = 0.14f),
        border = BorderStroke(
            1.dp,
            if (selected) IntraColors.BrandCyan else Color.White.copy(alpha = 0.25f)
        )
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
        )
    }
}
