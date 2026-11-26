package com.lantaservice.intra.ui.camera

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lantaservice.intra.ui.components.intraHeaderInsetsPadding
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.EquipmentScanParser
import com.lantaservice.intra.util.EquipmentScanResult
import com.lantaservice.intra.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Full-screen ML Kit Barcode + Label OCR scanner for printer/MFP serial numbers,
 * inventory tags, and model names with instant ticket match verification.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun EquipmentScannerScreen(
    taskId: Int,
    ticketText: String,
    onDismiss: () -> Unit,
    onResultConfirmed: (EquipmentScanResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var isScanningOcr by remember { mutableStateOf(false) }
    var liveBarcodeValues by remember { mutableStateOf<List<String>>(emptyList()) }
    var detectedResult by remember { mutableStateOf<EquipmentScanResult?>(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            runCatching { barcodeScanner.close() }
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        val pv = this
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = pv.surfaceProvider
                                }
                                val imageCapture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .build()
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .apply {
                                        var lastBarcodeCheckMs = 0L
                                        setAnalyzer(analysisExecutor) { imageProxy: ImageProxy ->
                                            val now = System.currentTimeMillis()
                                            val mediaImg = imageProxy.image
                                            if (mediaImg == null || detectedResult != null || now - lastBarcodeCheckMs < 240L) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }
                                            lastBarcodeCheckMs = now
                                            val inputImg = InputImage.fromMediaImage(
                                                mediaImg,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            barcodeScanner.process(inputImg)
                                                .addOnSuccessListener { barcodes ->
                                                    val vals = barcodes.mapNotNull { it.rawValue?.trim() }
                                                        .filter { it.isNotEmpty() }
                                                    if (vals.isNotEmpty()) {
                                                        liveBarcodeValues = vals
                                                        val parsed = EquipmentScanParser.parse(
                                                            barcodeValues = vals,
                                                            ocrLines = emptyList(),
                                                            ticketText = ticketText
                                                        )
                                                        if (parsed != null && detectedResult == null) {
                                                            detectedResult = parsed
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        }
                                    }

                                provider.unbindAll()
                                val cam = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture,
                                    analysis
                                )
                                cameraRef = cam
                                imageCaptureRef = imageCapture
                                hasFlash = cam.cameraInfo.hasFlashUnit()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )

            // Center label / barcode aiming box
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.84f)
                    .height(170.dp)
            ) {
                val frameCol = if (detectedResult != null) IntraColors.EmeraldSuccess else IntraColors.BrandCyan
                val strokeW = 3.5.dp.toPx()
                val corner = 26.dp.toPx()
                val w = size.width
                val h = size.height
                drawLine(frameCol, Offset(0f, 0f), Offset(corner, 0f), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(0f, 0f), Offset(0f, corner), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(w, 0f), Offset(w - corner, 0f), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(w, 0f), Offset(w, corner), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(0f, h), Offset(corner, h), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(0f, h), Offset(0f, h - corner), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(w, h), Offset(w - corner, h), strokeW, StrokeCap.Round)
                drawLine(frameCol, Offset(w, h), Offset(w, h - corner), strokeW, StrokeCap.Round)
            }

            // Top Bar
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
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = "Шильдик / штрихкод (#$taskId)",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
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
                                containerColor = if (torchEnabled) IntraColors.WarningAmber else Color.Black.copy(alpha = 0.55f),
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

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Наведите на штрихкод/DataMatrix или нажмите «Считать шильдик» для чтения модели и S/N",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Bottom Sheet: either Capture Label OCR button or Result Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.82f))
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val res = detectedResult
                if (res == null) {
                    Button(
                        onClick = {
                            val capture = imageCaptureRef ?: return@Button
                            isScanningOcr = true
                            val (rawFile, _) = ImageCompressor.createTempCameraFile(context, taskId)
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(rawFile).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            val inputImg = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    InputImage.fromFilePath(context, android.net.Uri.fromFile(rawFile))
                                                }
                                            }.getOrNull()
                                            val ocrLines = if (inputImg != null) {
                                                val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                                val txt = suspendCancellableCoroutine { cont ->
                                                    rec.process(inputImg)
                                                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                                                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                                                }
                                                txt?.textBlocks?.flatMap { b -> b.lines.map { it.text } }.orEmpty()
                                            } else {
                                                emptyList()
                                            }
                                            runCatching { rawFile.delete() }
                                            val parsed = EquipmentScanParser.parse(
                                                barcodeValues = liveBarcodeValues,
                                                ocrLines = ocrLines,
                                                ticketText = ticketText
                                            )
                                            isScanningOcr = false
                                            if (parsed != null) {
                                                detectedResult = parsed
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        isScanningOcr = false
                                        runCatching { rawFile.delete() }
                                    }
                                }
                            )
                        },
                        enabled = !isScanningOcr,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IntraColors.BrandPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        if (isScanningOcr) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Чтение шильдика...")
                        } else {
                            Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Считать текст шильдика (OCR)", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = IntraColors.SurfaceWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (res.matchesTicket) {
                                        false -> Icons.Rounded.WarningAmber
                                        else -> Icons.Rounded.QrCodeScanner
                                    },
                                    contentDescription = null,
                                    tint = when (res.matchesTicket) {
                                        true -> IntraColors.EmeraldSuccess
                                        false -> IntraColors.ErrorRed
                                        null -> IntraColors.BrandPrimary
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Считанные данные",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = IntraColors.TextPrimary
                                )
                            }
                            if (res.modelName.isNotBlank()) {
                                Text(
                                    text = "Модель: ${res.modelName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IntraColors.TextPrimary
                                )
                            }
                            if (res.serialNumber.isNotBlank()) {
                                Text(
                                    text = "Серийный номер (S/N): ${res.serialNumber}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IntraColors.BrandPrimary
                                )
                            }
                            if (res.inventoryNumber.isNotBlank()) {
                                Text(
                                    text = "Инв. номер: ${res.inventoryNumber}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IntraColors.TextPrimary
                                )
                            }
                            if (res.matchNote.isNotBlank()) {
                                Text(
                                    text = res.matchNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (res.matchesTicket) {
                                        true -> IntraColors.EmeraldDeep
                                        false -> IntraColors.ErrorRed
                                        null -> IntraColors.TextSecondary
                                    },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                detectedResult = null
                                liveBarcodeValues = emptyList()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Повторить", color = Color.White)
                        }
                        Button(
                            onClick = { onResultConfirmed(res) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = IntraColors.EmeraldSuccess,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Подставить", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
