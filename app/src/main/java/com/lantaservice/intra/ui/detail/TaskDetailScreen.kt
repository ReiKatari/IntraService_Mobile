package com.lantaservice.intra.ui.detail

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.lantaservice.intra.data.geo.GeoAddressSnapshot
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.PhotoDocumentType
import com.lantaservice.intra.data.model.PhotoDocumentTypeLock
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TaskSaveValidator
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.ui.camera.EquipmentScannerScreen
import com.lantaservice.intra.ui.camera.SmartCameraScreen
import com.lantaservice.intra.ui.components.FullscreenPhotoViewer
import com.lantaservice.intra.ui.components.IntraHeaderBanner
import com.lantaservice.intra.ui.components.StatusPill
import com.lantaservice.intra.ui.components.ViewerPhoto
import com.lantaservice.intra.ui.components.intraHeaderInsetsPadding
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.AvrCounterParser
import com.lantaservice.intra.util.AvrCounterRecognizer
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.EquipmentScanParser
import com.lantaservice.intra.util.EquipmentScanResult
import com.lantaservice.intra.util.ImageCompressor
import com.lantaservice.intra.util.NormalizedRect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private sealed interface PhotoTypePickerMode {
    data object ForCamera : PhotoTypePickerMode
    data object ForGallery : PhotoTypePickerMode
    data class Reclassify(val photoId: String, val currentType: PhotoDocumentType) : PhotoTypePickerMode
}

@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onSavedSuccess: (TicketSaveResult) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    var showSmartCamera by remember { mutableStateOf(false) }
    var showEquipmentScanner by remember { mutableStateOf(false) }
    var showSerialInputDialog by remember { mutableStateOf(false) }
    var pendingCameraPurpose by remember { mutableStateOf("photo") }
    var smartCameraPhotoIndex by remember { mutableIntStateOf(1) }
    var activePhotoTypeForCapture by rememberSaveable { mutableStateOf(PhotoDocumentType.AVR) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var photoTypePickerMode by remember { mutableStateOf<PhotoTypePickerMode?>(null) }
    var avrCropTargetPhoto by remember { mutableStateOf<PendingPhoto?>(null) }
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val latestPendingAvr = remember(state.pendingPhotos) {
        state.pendingPhotos.lastOrNull { it.documentType == PhotoDocumentType.AVR }
    }
    val expectedEquipment = remember(state.detail?.name, state.detail?.description) {
        state.detail?.let { EquipmentScanParser.extractExpectedFromTicket("${it.name}\n${it.description}") }
    }

    val hasUnlockedPending = remember(state.pendingPhotos, nowEpochMs) {
        state.pendingPhotos.any { PhotoDocumentTypeLock.canChangeType(it.createdAtEpochMs, nowEpochMs) }
    }

    LaunchedEffect(hasUnlockedPending) {
        if (!hasUnlockedPending) return@LaunchedEffect
        while (true) {
            nowEpochMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    // Automatic 1-minute silent refresh while ticket detail is active
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000L)
                viewModel.silentAutoRefresh()
            }
        }
    }

    val takePictureFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uriStr = pendingCameraUriString
        pendingCameraUriString = null
        if (success && uriStr != null) {
            viewModel.addCapturedPhoto(
                context = context,
                sourceUri = Uri.parse(uriStr),
                documentType = activePhotoTypeForCapture,
                tempFileToDelete = true
            )
        }
    }

    fun launchSystemCameraFallback() {
        val (_, uri) = ImageCompressor.createTempCameraFile(context, state.taskId)
        pendingCameraUriString = uri.toString()
        takePictureFallbackLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingCameraPurpose == "equipment") {
                showEquipmentScanner = true
            } else {
                smartCameraPhotoIndex = viewModel.nextPhotoIndex()
                showSmartCamera = true
            }
        }
        pendingCameraPurpose = "photo"
    }

    fun startCameraWithType(docType: PhotoDocumentType) {
        activePhotoTypeForCapture = docType
        pendingCameraPurpose = "photo"
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            smartCameraPhotoIndex = viewModel.nextPhotoIndex()
            showSmartCamera = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun startEquipmentScanner() {
        pendingCameraPurpose = "equipment"
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showEquipmentScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPhotoUris(
                context = context,
                uris = uris,
                documentType = activePhotoTypeForCapture
            )
        }
    }

    photoTypePickerMode?.let { mode ->
        val preselected = when (mode) {
            is PhotoTypePickerMode.Reclassify -> mode.currentType
            else -> activePhotoTypeForCapture
        }
        PhotoTypePickerDialog(
            title = if (mode is PhotoTypePickerMode.Reclassify) {
                "Изменить тип документа"
            } else {
                "Выберите формат фото"
            },
            subtitle = if (mode is PhotoTypePickerMode.Reclassify) {
                "Тип можно сменить в течение 5 минут после съёмки, затем он фиксируется"
            } else {
                "Сразу укажите назначение снимка (формат можно поменять в течение 5 минут)"
            },
            currentSelection = preselected,
            onDismiss = { photoTypePickerMode = null },
            onSelect = { chosenType ->
                photoTypePickerMode = null
                when (mode) {
                    is PhotoTypePickerMode.ForCamera -> startCameraWithType(chosenType)
                    is PhotoTypePickerMode.ForGallery -> {
                        activePhotoTypeForCapture = chosenType
                        pickGalleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    is PhotoTypePickerMode.Reclassify -> {
                        viewModel.changePhotoDocumentType(context, mode.photoId, chosenType)
                    }
                }
            }
        )
    }

    if (state.showManualCounterPrompt) {
        CounterInputDialog(
            initialValue = state.counterValue,
            reasonMessage = state.counterPromptReason,
            hasAvrPhoto = latestPendingAvr != null,
            onOpenCropDialog = {
                latestPendingAvr?.let { avr ->
                    viewModel.dismissManualCounterDialog()
                    avrCropTargetPhoto = avr
                }
            },
            onDismiss = viewModel::dismissManualCounterDialog,
            onConfirm = viewModel::submitManualCounter
        )
    }

    avrCropTargetPhoto?.let { targetPhoto ->
        val savedTemplate = remember(targetPhoto.id) {
            AvrCounterRecognizer.loadAvrTemplateRoi(context)
        }
        AvrCounterCropDialog(
            photo = targetPhoto,
            initialRoi = savedTemplate,
            isRecognizing = state.isRecognizingRoiCrop,
            onDismiss = {
                if (!state.isRecognizingRoiCrop) {
                    avrCropTargetPhoto = null
                }
            },
            onRecognizeRegion = { roi, saveTemplate ->
                viewModel.recognizeCounterFromCrop(
                    context = context,
                    photoFile = targetPhoto.file,
                    roi = roi,
                    saveTemplate = saveTemplate
                ) {
                    avrCropTargetPhoto = null
                }
            }
        )
    }

    if (showEquipmentScanner) {
        val ticketTextForScanner = state.detail?.let { "${it.name}\n${it.description}" }.orEmpty()
        EquipmentScannerScreen(
            taskId = state.taskId,
            ticketText = ticketTextForScanner,
            onDismiss = { showEquipmentScanner = false },
            onResultConfirmed = { scanResult ->
                showEquipmentScanner = false
                viewModel.onEquipmentScanned(scanResult)
            }
        )
    }

    if (showSerialInputDialog) {
        SerialNumberInputDialog(
            initialValue = state.equipmentScan?.serialNumber.orEmpty(),
            expectedSerial = expectedEquipment?.serialNumber.orEmpty(),
            ticketText = state.detail?.let { "${it.name}\n${it.description}" }.orEmpty(),
            onOpenScanner = {
                showSerialInputDialog = false
                startEquipmentScanner()
            },
            onClear = {
                showSerialInputDialog = false
                viewModel.clearEquipmentScan()
            },
            onDismiss = { showSerialInputDialog = false },
            onConfirm = { serialNumber ->
                showSerialInputDialog = false
                viewModel.updateSerialNumberManual(serialNumber)
            }
        )
    }

    if (showSmartCamera) {
        SmartCameraScreen(
            taskId = state.taskId,
            photoIndex = smartCameraPhotoIndex,
            documentType = activePhotoTypeForCapture,
            onDismiss = { showSmartCamera = false },
            onPhotoAccepted = { photo ->
                showSmartCamera = false
                viewModel.addPreparedPhoto(context, photo)
            },
            onCameraFallbackRequested = {
                showSmartCamera = false
                launchSystemCameraFallback()
            }
        )
    }

    state.viewerPhoto?.let { photo ->
        FullscreenPhotoViewer(
            photo = photo,
            onDismiss = viewModel::closePhotoViewer
        )
    }

    Scaffold(
        containerColor = IntraColors.BackgroundIce,
        topBar = {
            IntraHeaderBanner(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .intraHeaderInsetsPadding()
                        .padding(horizontal = 10.dp, vertical = if (isLandscape) 4.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Назад"
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Заявка #${state.taskId}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            state.detail?.let { d ->
                                Text(
                                    text = d.statusName,
                                    color = Color.White.copy(alpha = 0.88f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.loadDetail()
                            viewModel.refreshGeolocation()
                        },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
                    }
                }
            }
        },
        bottomBar = {
            if (state.detail != null) {
                SaveStickyBottomBar(
                    state = state,
                    onSaveClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.saveChanges(onSavedSuccess)
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = IntraColors.BrandPrimary)
                    }
                }
                state.loadError != null && state.detail == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = IntraColors.ErrorRed,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = state.loadError.orEmpty(),
                            color = IntraColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = viewModel::loadDetail,
                            colors = ButtonDefaults.buttonColors(containerColor = IntraColors.BrandPrimary)
                        ) {
                            Text("Повторить")
                        }
                    }
                }
                state.detail != null -> {
                    val detail = state.detail!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        state.queuedOutbox?.let { outboxEntry ->
                            item(key = "outbox_banner") {
                                DeferredOutboxCard(
                                    entry = outboxEntry,
                                    isSaving = state.isSaving,
                                    onRetryNow = {
                                        viewModel.retryDeferredSendNow(onSavedSuccess)
                                    },
                                    onCancelDeferred = viewModel::cancelDeferredSend
                                )
                            }
                        }

                        item {
                            TicketInfoCard(
                                detail = detail,
                                onCallPhone = { phone ->
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                    runCatching { context.startActivity(dialIntent) }
                                }
                            )
                        }

                        item {
                            QuickRepairedStatusCard(
                                detail = detail,
                                markAsRepaired = state.markAsRepaired,
                                commentText = state.commentText,
                                isSaving = state.isSaving,
                                onToggleRepaired = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleMarkAsRepaired()
                                },
                                onCommentChange = viewModel::onCommentChange
                            )
                        }

                        item {
                            AttachmentsCard(
                                detail = detail,
                                pendingPhotos = state.pendingPhotos,
                                nowEpochMs = nowEpochMs,
                                counterValue = state.counterValue,
                                counterAutoDetected = state.counterAutoDetected,
                                isRecognizingCounter = state.isRecognizingCounter,
                                equipmentScan = state.equipmentScan,
                                expectedSerial = expectedEquipment?.serialNumber.orEmpty(),
                                isProcessingPhoto = state.isProcessingPhoto,
                                isSaving = state.isSaving,
                                onTakePhoto = {
                                    photoTypePickerMode = PhotoTypePickerMode.ForCamera
                                },
                                onPickFromGallery = {
                                    photoTypePickerMode = PhotoTypePickerMode.ForGallery
                                },
                                onAddForCategory = { docType ->
                                    activePhotoTypeForCapture = docType
                                    startCameraWithType(docType)
                                },
                                onChangePhotoType = { pending ->
                                    photoTypePickerMode = PhotoTypePickerMode.Reclassify(
                                        photoId = pending.id,
                                        currentType = pending.documentType
                                    )
                                },
                                onEditCounter = viewModel::openManualCounterDialog,
                                onSelectCounterRegion = {
                                    latestPendingAvr?.let { avrCropTargetPhoto = it }
                                },
                                onEditSerial = { showSerialInputDialog = true },
                                onRemovePending = viewModel::removePendingPhoto,
                                onPreviewPending = { pending ->
                                    viewModel.openPhotoViewer(
                                        ViewerPhoto(
                                            title = "${pending.documentType.shortTitle} - ${pending.nameWithoutExt}.jpg",
                                            model = pending.file
                                        )
                                    )
                                },
                                onClickExistingAttachment = { att ->
                                    if (att.isImage) {
                                        viewModel.openPhotoViewer(
                                            ViewerPhoto(title = att.name, model = att.url)
                                        )
                                    } else {
                                        viewModel.downloadAndOpenAttachment(att) { uri, name ->
                                            val ext = name.substringAfterLast('.', "").lowercase()
                                            val mime = MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(ext) ?: "*/*"
                                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mime)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching { context.startActivity(viewIntent) }
                                        }
                                    }
                                }
                            )
                        }

                        if (detail.history.isNotEmpty()) {
                            item {
                                HistoryCard(
                                    detail = detail,
                                    expanded = state.isHistoryExpanded,
                                    onToggleExpand = viewModel::toggleHistoryExpanded
                                )
                            }
                        }

                        item {
                            GeolocationCard(
                                snapshot = state.geoSnapshot,
                                isLoading = state.isLoadingGeo,
                                onRefreshGeo = { viewModel.refreshGeolocation() },
                                onCopyText = { label, text, msg ->
                                    copyToClipboard(context, label, text, msg)
                                },
                                onOpenMap = { lat, lon, label ->
                                    val encoded = Uri.encode(label.ifBlank { "Заявка #${state.taskId}" })
                                    val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($encoded)")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geoUri)) }
                                        .onFailure {
                                            Toast.makeText(
                                                context,
                                                "На устройстве не найдено приложение карт",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

@Composable
private fun GeoAddressDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = IntraColors.TextTertiary,
            modifier = Modifier.width(136.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = IntraColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GeoMetricTile(
    title: String,
    value: String,
    subtitle: String? = null,
    valueColor: Color = IntraColors.TextPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = IntraColors.BackgroundIce,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = IntraColors.TextTertiary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = IntraColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GeolocationCard(
    snapshot: GeoAddressSnapshot?,
    isLoading: Boolean,
    onRefreshGeo: () -> Unit,
    onCopyText: (label: String, text: String, toastMessage: String) -> Unit,
    onOpenMap: (lat: Double, lon: Double, label: String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(
            1.dp,
            if (snapshot?.jammerSuspected == true) IntraColors.WarningAmber.copy(alpha = 0.55f) else IntraColors.SubtleBorder
        ),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header + Status Pill + Refresh Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = if (snapshot?.jammerSuspected == true) IntraColors.WarningAmber else IntraColors.BrandPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Геолокация на объекте",
                        style = MaterialTheme.typography.titleMedium,
                        color = IntraColors.TextPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when {
                            isLoading -> IntraColors.BrandPrimary.copy(alpha = 0.12f)
                            snapshot?.jammerSuspected == true -> IntraColors.WarningTint
                            snapshot != null -> IntraColors.EmeraldTint
                            else -> IntraColors.BackgroundIce
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = IntraColors.BrandPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                            Text(
                                text = when {
                                    isLoading && snapshot == null -> "Поиск..."
                                    isLoading -> "Уточнение..."
                                    snapshot?.jammerSuspected == true -> "Помехи GPS"
                                    snapshot != null -> "Определено"
                                    else -> "Ожидание"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isLoading -> IntraColors.BrandPrimary
                                    snapshot?.jammerSuspected == true -> IntraColors.WarningAmber
                                    snapshot != null -> IntraColors.EmeraldDeep
                                    else -> IntraColors.TextSecondary
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = onRefreshGeo,
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = IntraColors.BackgroundIce,
                            contentColor = IntraColors.BrandPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Обновить геолокацию",
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            if (snapshot == null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = IntraColors.BackgroundIce,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isLoading) {
                            "Определяем координаты и полный адрес объекта (GPS, Wi-Fi и базовые станции)..."
                        } else {
                            "Геолокация будет определена автоматически при появлении сигнала или нажмите кнопку обновления."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextSecondary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                // Group 1: Structured Object Address
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = IntraColors.BackgroundIce,
                    border = BorderStroke(1.dp, IntraColors.SubtleBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Адрес объекта",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = IntraColors.BrandPrimary
                            )
                            if (snapshot.fullAddress.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            onCopyText("address", snapshot.fullAddress, "Адрес скопирован")
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Копировать адрес",
                                        tint = IntraColors.BrandPrimary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Копировать",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = IntraColors.BrandPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (snapshot.placeName.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = IntraColors.BrandPrimary.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = "Объект: ${snapshot.placeName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IntraColors.BrandPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = snapshot.fullAddress.ifBlank {
                                "Координаты зафиксированы - полный адрес загрузится при подключении к сети"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = IntraColors.TextPrimary
                        )

                        val districtCombined = listOf(snapshot.district, snapshot.suburb)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase() }
                            .joinToString(", ")

                        val streetHouseCombined = buildList {
                            snapshot.street.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                            snapshot.houseNumber.trim().takeIf { it.isNotEmpty() }?.let { house ->
                                add(
                                    if (house.lowercase().startsWith("д.") || house.lowercase().startsWith("дом")) {
                                        house
                                    } else {
                                        "дом $house"
                                    }
                                )
                            }
                        }.joinToString(", ")

                        val hasBreakdown = snapshot.postalCode.isNotBlank() ||
                            snapshot.region.isNotBlank() ||
                            districtCombined.isNotBlank() ||
                            snapshot.locality.isNotBlank() ||
                            streetHouseCombined.isNotBlank()

                        if (hasBreakdown) {
                            HorizontalDivider(color = IntraColors.SubtleBorder)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                snapshot.postalCode.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Почтовый индекс", value = it)
                                }
                                snapshot.region.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Регион", value = it)
                                }
                                districtCombined.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Район / округ", value = it)
                                }
                                snapshot.locality.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Населённый пункт", value = it)
                                }
                                streetHouseCombined.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Улица и дом", value = it)
                                }
                                snapshot.entrance.takeIf { it.isNotBlank() }?.let {
                                    GeoAddressDetailRow(label = "Подъезд", value = it)
                                }
                            }
                        }
                    }
                }

                // Group 2: Navigation & Accuracy Metrics (clean non-truncated layout)
                val accuracyColor = when {
                    snapshot.accuracyMeters <= 0f -> IntraColors.TextSecondary
                    snapshot.accuracyMeters <= 30f -> IntraColors.EmeraldDeep
                    snapshot.accuracyMeters <= 80f -> IntraColors.BrandPrimary
                    else -> IntraColors.WarningAmber
                }
                val accuracyText = if (snapshot.accuracyMeters > 0f) {
                    "±${snapshot.accuracyMeters.roundToInt()} м - ${snapshot.accuracyQualityLabel()}"
                } else {
                    "Не определена"
                }
                val altitudeText = snapshot.altitudeMeters?.let { alt ->
                    "${alt.roundToInt()} метров"
                } ?: "Не определена"
                val sourceSubtitle = buildList {
                    if (snapshot.providerCount > 0) add("Активных источников: ${snapshot.providerCount}")
                    snapshot.geocoderProvider.takeIf { it.isNotBlank() }?.let { add("Геокодер: $it") }
                }.joinToString(" • ").takeIf { it.isNotBlank() }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GeoMetricTile(
                            title = "Координаты",
                            value = snapshot.formatCoordinates(),
                            modifier = Modifier.weight(1f)
                        )
                        GeoMetricTile(
                            title = "Точность",
                            value = accuracyText,
                            valueColor = accuracyColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GeoMetricTile(
                            title = "Время фиксации",
                            value = snapshot.capturedAtText,
                            modifier = Modifier.weight(1f)
                        )
                        GeoMetricTile(
                            title = "Высота над уровнем моря",
                            value = altitudeText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    GeoMetricTile(
                        title = "Источник сигнала",
                        value = snapshot.sourceLabel,
                        subtitle = sourceSubtitle,
                        valueColor = if (snapshot.jammerSuspected) IntraColors.WarningAmber else IntraColors.EmeraldDeep,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Quick actions bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onCopyText("coords", snapshot.formatCoordinates(), "Координаты скопированы")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, IntraColors.SubtleBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Координаты",
                            style = MaterialTheme.typography.labelMedium,
                            color = IntraColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onOpenMap(
                                snapshot.latitude,
                                snapshot.longitude,
                                snapshot.fullAddress.ifBlank { snapshot.placeName }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, IntraColors.BrandPrimary.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Map,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "На карте",
                            style = MaterialTheme.typography.labelMedium,
                            color = IntraColors.BrandPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Group 3: Navigation Warning / Anti-Jammer Note
                snapshot.warningNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = IntraColors.WarningTint,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = IntraColors.WarningAmber,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = IntraColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTypePickerDialog(
    title: String,
    subtitle: String,
    currentSelection: PhotoDocumentType,
    onDismiss: () -> Unit,
    onSelect: (PhotoDocumentType) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = IntraColors.SurfaceWhite,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(IntraColors.BrandPrimary, IntraColors.BrandCyan)
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PhotoDocumentType.entries.forEach { docType ->
                        val isSelected = docType == currentSelection
                        val accent = when (docType) {
                            PhotoDocumentType.AVR -> IntraColors.BrandPrimary
                            PhotoDocumentType.TEST_LIST -> IntraColors.EmeraldDeep
                            PhotoDocumentType.CONFIG_LIST -> IntraColors.BrandCyan
                        }
                        val icon = when (docType) {
                            PhotoDocumentType.AVR -> Icons.Rounded.Description
                            PhotoDocumentType.TEST_LIST -> Icons.Rounded.Print
                            PhotoDocumentType.CONFIG_LIST -> Icons.Rounded.Settings
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelect(docType) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) accent.copy(alpha = 0.08f) else IntraColors.BackgroundIce,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accent else IntraColors.SubtleBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = accent.copy(alpha = 0.14f),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = docType.shortTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = IntraColors.TextPrimary
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (docType.isRequired) {
                                                IntraColors.WarningTint
                                            } else {
                                                IntraColors.SurfaceWhite
                                            }
                                        ) {
                                            Text(
                                                text = if (docType.isRequired) "Обязательно" else "Дополнительно",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (docType.isRequired) {
                                                    IntraColors.WarningAmber
                                                } else {
                                                    IntraColors.TextSecondary
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = docType.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = IntraColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Отмена", color = IntraColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterInputDialog(
    initialValue: String,
    reasonMessage: String?,
    hasAvrPhoto: Boolean = false,
    onOpenCropDialog: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var inputText by remember(initialValue) { mutableStateOf(initialValue) }
    val digitsOnly = TaskSaveValidator.normalizeCounterDigits(inputText)
    val isValid = TaskSaveValidator.isValidCounter(digitsOnly)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = IntraColors.SurfaceWhite,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FactCheck,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Счётчик копий (АВР)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = IntraColors.TextPrimary
                    )
                }

                Text(
                    text = reasonMessage
                        ?: "Укажите или скорректируйте количество копий, вписанное в Акт выполненных работ (обязательно для сохранения).",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reasonMessage != null) IntraColors.WarningAmber else IntraColors.TextSecondary
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        inputText = TaskSaveValidator.normalizeCounterDigits(raw)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Количество копий") },
                    placeholder = { Text("Например: 145230") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (isValid) onConfirm(digitsOnly) }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IntraColors.BrandPrimary,
                        unfocusedBorderColor = IntraColors.SubtleBorder
                    )
                )

                if (digitsOnly.isNotEmpty()) {
                    Text(
                        text = "Значение к отправке: ${AvrCounterParser.formatCounterDisplay(digitsOnly)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = IntraColors.EmeraldDeep,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (hasAvrPhoto) {
                    OutlinedButton(
                        onClick = onOpenCropDialog,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, IntraColors.BrandPrimary.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CropFree,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Выделить область счётчика на АВР",
                            color = IntraColors.BrandPrimary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = IntraColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(digitsOnly) },
                        enabled = isValid,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IntraColors.BrandPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Применить")
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketInfoCard(
    detail: TicketDetail,
    onCallPhone: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(
                    text = detail.statusName,
                    isRepaired = detail.isCurrentlyRepaired,
                    isOverdue = detail.isOverdue && !detail.isCurrentlyRepaired
                )
                if (detail.priorityName.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = detail.priorityName,
                        style = MaterialTheme.typography.labelMedium,
                        color = IntraColors.TextSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            Text(
                text = detail.name,
                style = MaterialTheme.typography.titleLarge,
                color = IntraColors.TextPrimary
            )

            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodyMedium,
                color = IntraColors.TextSecondary
            )

            HorizontalDivider(color = IntraColors.SubtleBorder)

            DetailMetaRow(label = "Сервис", value = detail.serviceName.ifBlank { "Не указан" })
            DetailMetaRow(label = "Заявитель", value = detail.creatorName)
            if (!detail.creatorPhone.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onCallPhone(detail.creatorPhone) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Телефон",
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextTertiary,
                        modifier = Modifier.width(104.dp)
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Phone,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = detail.creatorPhone,
                            style = MaterialTheme.typography.bodySmall,
                            color = IntraColors.BrandPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (detail.createdText.isNotBlank()) {
                DetailMetaRow(label = "Создана", value = detail.createdText)
            }
            if (detail.changedText.isNotBlank()) {
                DetailMetaRow(label = "Изменена", value = detail.changedText)
            }
            DetailMetaRow(
                label = "Срок",
                value = detail.deadlineText,
                valueColor = if (detail.isOverdue) IntraColors.ErrorRed else IntraColors.TextPrimary
            )
            DetailMetaRow(label = "Исполнители", value = detail.executors)
        }
    }
}

@Composable
private fun DetailMetaRow(
    label: String,
    value: String,
    valueColor: Color = IntraColors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = IntraColors.TextTertiary,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickRepairedStatusCard(
    detail: TicketDetail,
    markAsRepaired: Boolean,
    commentText: String,
    isSaving: Boolean,
    onToggleRepaired: () -> Unit,
    onCommentChange: (String) -> Unit
) {
    val toggleBg by animateColorAsState(
        targetValue = if (markAsRepaired) IntraColors.EmeraldSuccess else IntraColors.EmeraldTint,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "repaired_toggle_bg"
    )
    val toggleFg by animateColorAsState(
        targetValue = if (markAsRepaired) Color.White else IntraColors.EmeraldDeep,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "repaired_toggle_fg"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    tint = IntraColors.BrandPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Выполнение и статус",
                    style = MaterialTheme.typography.titleMedium,
                    color = IntraColors.TextPrimary
                )
            }

            when {
                detail.isCurrentlyRepaired -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = IntraColors.EmeraldTint
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = IntraColors.EmeraldDeep
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Статус «${detail.statusName}» уже установлен",
                                style = MaterialTheme.typography.titleSmall,
                                color = IntraColors.EmeraldDeep
                            )
                        }
                    }
                }
                detail.canSetRepaired -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isSaving, onClick = onToggleRepaired),
                        shape = RoundedCornerShape(16.dp),
                        color = toggleBg,
                        border = BorderStroke(
                            1.dp,
                            if (markAsRepaired) IntraColors.EmeraldDeep else IntraColors.EmeraldSuccess.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (markAsRepaired) {
                                        "Выбран статус: ${detail.repairedStatusName}"
                                    } else {
                                        "Поставить статус «${detail.repairedStatusName}»"
                                    },
                                    color = toggleFg,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (markAsRepaired) {
                                        "После сохранения заявка перейдет на вкладку «Выполнено»"
                                    } else {
                                        "Нажмите, чтобы отметить работу выполненной"
                                    },
                                    color = toggleFg.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = toggleFg,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                !detail.repairedUnavailableReason.isNullOrBlank() -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = IntraColors.WarningTint
                    ) {
                        Text(
                            text = detail.repairedUnavailableReason,
                            color = IntraColors.WarningAmber,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (detail.canComment) {
                val commentRequired = markAsRepaired && detail.isCommentRequiredForRepaired
                OutlinedTextField(
                    value = commentText,
                    onValueChange = onCommentChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    minLines = 2,
                    maxLines = 4,
                    label = {
                        Text(
                            if (commentRequired) {
                                "Комментарий (обязательно)"
                            } else {
                                "Комментарий к заявке (необязательно)"
                            }
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IntraColors.BrandPrimary,
                        unfocusedBorderColor = IntraColors.SubtleBorder
                    )
                )
            }
        }
    }
}

@Composable
private fun AttachmentsCard(
    detail: TicketDetail,
    pendingPhotos: List<PendingPhoto>,
    nowEpochMs: Long,
    counterValue: String,
    counterAutoDetected: Boolean,
    isRecognizingCounter: Boolean,
    equipmentScan: EquipmentScanResult? = null,
    expectedSerial: String = "",
    isProcessingPhoto: Boolean,
    isSaving: Boolean,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onAddForCategory: (PhotoDocumentType) -> Unit,
    onChangePhotoType: (PendingPhoto) -> Unit,
    onEditCounter: () -> Unit,
    onSelectCounterRegion: () -> Unit = {},
    onEditSerial: () -> Unit = {},
    onRemovePending: (String) -> Unit,
    onPreviewPending: (PendingPhoto) -> Unit,
    onClickExistingAttachment: (TaskAttachment) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AttachFile,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Документы и фотоотчёт",
                        style = MaterialTheme.typography.titleMedium,
                        color = IntraColors.TextPrimary
                    )
                }
                Text(
                    text = "${detail.attachments.size + pendingPhotos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = IntraColors.TextSecondary
                )
            }

            if (detail.canAttachFiles) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTakePhoto,
                        enabled = !isSaving && !isProcessingPhoto,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IntraColors.BrandPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Сделать фото", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = onPickFromGallery,
                        enabled = !isSaving && !isProcessingPhoto,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, IntraColors.BrandPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Галерея", color = IntraColors.BrandPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text(
                    text = "Прикрепление файлов ограничено правами доступа",
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextTertiary
                )
            }

            AnimatedVisibility(visible = isProcessingPhoto) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = IntraColors.BrandPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Повышение чёткости и сжатие фото...",
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextSecondary
                    )
                }
            }

            // 3 Structured Photo Document Blocks
            PhotoDocumentType.entries.forEach { docType ->
                val catPending = pendingPhotos.filter { it.documentType == docType }
                val catExisting = detail.attachments.filter {
                    PhotoDocumentTypeLock.detectTypeFromFileName(it.name) == docType
                }
                PhotoCategoryBlock(
                    docType = docType,
                    pendingPhotos = catPending,
                    existingAttachments = catExisting,
                    nowEpochMs = nowEpochMs,
                    canAttachFiles = detail.canAttachFiles,
                    isSaving = isSaving,
                    onQuickCapture = { onAddForCategory(docType) },
                    onChangePhotoType = onChangePhotoType,
                    onRemovePending = onRemovePending,
                    onPreviewPending = onPreviewPending,
                    onClickExistingAttachment = onClickExistingAttachment
                ) {
                    if (docType == PhotoDocumentType.AVR) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AvrCounterBanner(
                                counterValue = counterValue,
                                counterAutoDetected = counterAutoDetected,
                                isRecognizing = isRecognizingCounter,
                                isSaving = isSaving,
                                hasAvrPhoto = catPending.isNotEmpty(),
                                onEditCounter = onEditCounter,
                                onSelectRegion = onSelectCounterRegion
                            )
                            AvrSerialBanner(
                                equipmentScan = equipmentScan,
                                expectedSerial = expectedSerial,
                                isSaving = isSaving,
                                onEditSerial = onEditSerial
                            )
                        }
                    }
                }
            }

            // Unclassified existing server files (from web portal or previous versions)
            val otherAttachments = detail.attachments.filter {
                PhotoDocumentTypeLock.detectTypeFromFileName(it.name) == null
            }
            if (otherAttachments.isNotEmpty()) {
                Text(
                    text = "Прочие вложения в заявке (${otherAttachments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = IntraColors.TextSecondary
                )
                val otherImages = otherAttachments.filter { it.isImage }
                val otherFiles = otherAttachments.filter { !it.isImage }

                if (otherImages.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(otherImages, key = { it.id }) { img ->
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(IntraColors.BackgroundIce)
                                    .clickable { onClickExistingAttachment(img) }
                            ) {
                                AsyncImage(
                                    model = img.url,
                                    contentDescription = img.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                otherFiles.forEach { fileAtt ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClickExistingAttachment(fileAtt) },
                        shape = RoundedCornerShape(12.dp),
                        color = IntraColors.BackgroundIce
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = IntraColors.BrandPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = fileAtt.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IntraColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Открыть",
                                tint = IntraColors.BrandPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCategoryBlock(
    docType: PhotoDocumentType,
    pendingPhotos: List<PendingPhoto>,
    existingAttachments: List<TaskAttachment>,
    nowEpochMs: Long,
    canAttachFiles: Boolean,
    isSaving: Boolean,
    onQuickCapture: () -> Unit,
    onChangePhotoType: (PendingPhoto) -> Unit,
    onRemovePending: (String) -> Unit,
    onPreviewPending: (PendingPhoto) -> Unit,
    onClickExistingAttachment: (TaskAttachment) -> Unit,
    extraContent: @Composable () -> Unit = {}
) {
    val totalCount = pendingPhotos.size + existingAttachments.size
    val hasAny = totalCount > 0
    val borderColor = when {
        hasAny -> IntraColors.EmeraldSuccess.copy(alpha = 0.45f)
        docType.isRequired -> IntraColors.WarningAmber.copy(alpha = 0.50f)
        else -> IntraColors.SubtleBorder
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = IntraColors.BackgroundIce,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = docType.shortTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = IntraColors.TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = when {
                                hasAny -> IntraColors.EmeraldTint
                                docType.isRequired -> IntraColors.WarningTint
                                else -> IntraColors.SurfaceWhite
                            }
                        ) {
                            Text(
                                text = when {
                                    hasAny -> "Приложено: $totalCount"
                                    docType.isRequired -> "Обязательно"
                                    else -> "Необязательно"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    hasAny -> IntraColors.EmeraldDeep
                                    docType.isRequired -> IntraColors.WarningAmber
                                    else -> IntraColors.TextSecondary
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (docType.fullTitle != docType.shortTitle) {
                        Text(
                            text = docType.fullTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = IntraColors.TextTertiary
                        )
                    }
                }

                if (canAttachFiles) {
                    Spacer(modifier = Modifier.width(10.dp))
                    val btnColor = when (docType) {
                        PhotoDocumentType.AVR -> IntraColors.BrandPrimary
                        PhotoDocumentType.TEST_LIST -> IntraColors.EmeraldSuccess
                        PhotoDocumentType.CONFIG_LIST -> IntraColors.BrandCyan
                    }
                    Button(
                        onClick = onQuickCapture,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = btnColor,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.heightIn(min = 42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = docType.captureButtonLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (pendingPhotos.isNotEmpty() || existingAttachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pendingPhotos, key = { it.id }) { pending ->
                        val canSwitchType = PhotoDocumentTypeLock.canChangeType(
                            pending.createdAtEpochMs,
                            nowEpochMs
                        )
                        val remainingLabel = PhotoDocumentTypeLock.formatRemainingMmSs(
                            pending.createdAtEpochMs,
                            nowEpochMs
                        )
                        Column(
                            modifier = Modifier.width(126.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(126.dp, 108.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(IntraColors.SurfaceWhite)
                                    .clickable { onPreviewPending(pending) }
                            ) {
                                AsyncImage(
                                    model = pending.file,
                                    contentDescription = pending.nameWithoutExt,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                                    color = if (pending.isBlurryWarning) {
                                        IntraColors.WarningAmber
                                    } else {
                                        IntraColors.EmeraldSuccess
                                    },
                                    modifier = Modifier.align(Alignment.TopStart)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (pending.isBlurryWarning) {
                                                Icons.Rounded.WarningAmber
                                            } else {
                                                Icons.Rounded.AutoFixHigh
                                            },
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = DateAndTextFormatter.formatBytes(pending.sizeBytes),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (!isSaving) {
                                    IconButton(
                                        onClick = { onRemovePending(pending.id) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(24.dp),
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = Color.Black.copy(alpha = 0.6f),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Удалить",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = canSwitchType && !isSaving) {
                                        onChangePhotoType(pending)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (canSwitchType) {
                                    IntraColors.BrandPrimary.copy(alpha = 0.12f)
                                } else {
                                    IntraColors.EmeraldTint
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (canSwitchType) Icons.Rounded.SwapHoriz else Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = if (canSwitchType) IntraColors.BrandPrimary else IntraColors.EmeraldDeep,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (canSwitchType) {
                                            "Сменить ($remainingLabel)"
                                        } else {
                                            "Зафиксировано"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (canSwitchType) IntraColors.BrandPrimary else IntraColors.EmeraldDeep,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    items(existingAttachments, key = { it.id }) { att ->
                        Box(
                            modifier = Modifier
                                .size(100.dp, 108.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(IntraColors.SurfaceWhite)
                                .clickable { onClickExistingAttachment(att) }
                        ) {
                            if (att.isImage) {
                                AsyncImage(
                                    model = att.url,
                                    contentDescription = att.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                        tint = IntraColors.BrandPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = att.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(topStart = 8.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.align(Alignment.BottomEnd)
                            ) {
                                Text(
                                    text = "На сервере",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = if (docType.isRequired) {
                        "Пока нет фото. Необходимо приложить для сохранения заявки."
                    } else {
                        "Пока нет фото (прикладывается при необходимости)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextTertiary
                )
            }

            extraContent()
        }
    }
}

@Composable
private fun AvrCounterBanner(
    counterValue: String,
    counterAutoDetected: Boolean,
    isRecognizing: Boolean,
    isSaving: Boolean,
    hasAvrPhoto: Boolean = false,
    onEditCounter: () -> Unit,
    onSelectRegion: () -> Unit = {}
) {
    val hasCounter = TaskSaveValidator.isValidCounter(counterValue)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isSaving && !isRecognizing, onClick = onEditCounter),
        shape = RoundedCornerShape(12.dp),
        color = when {
            isRecognizing -> IntraColors.SurfaceWhite
            hasCounter -> IntraColors.EmeraldTint
            else -> IntraColors.WarningTint
        },
        border = BorderStroke(
            1.dp,
            when {
                isRecognizing -> IntraColors.BrandPrimary.copy(alpha = 0.35f)
                hasCounter -> IntraColors.EmeraldSuccess.copy(alpha = 0.45f)
                else -> IntraColors.WarningAmber.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isRecognizing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = IntraColors.BrandPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Распознаём счётчик копий на АВР...",
                            style = MaterialTheme.typography.bodySmall,
                            color = IntraColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (hasCounter) {
                    Text(
                        text = "Счётчик копий: ${AvrCounterParser.formatCounterDisplay(counterValue)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = IntraColors.EmeraldDeep
                    )
                    Text(
                        text = if (counterAutoDetected) {
                            "Автоматически распознан на фото АВР"
                        } else {
                            "Указано инженером вручную"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = IntraColors.EmeraldDeep.copy(alpha = 0.85f)
                    )
                } else {
                    Text(
                        text = "Счётчик копий не указан",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = IntraColors.WarningAmber
                    )
                    Text(
                        text = "Определится после фото АВР или нажмите для ввода",
                        style = MaterialTheme.typography.labelSmall,
                        color = IntraColors.TextSecondary
                    )
                }
            }

            if (!isRecognizing) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasAvrPhoto) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !isSaving, onClick = onSelectRegion),
                            shape = RoundedCornerShape(10.dp),
                            color = IntraColors.BrandPrimary.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CropFree,
                                    contentDescription = null,
                                    tint = IntraColors.BrandPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Область",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = IntraColors.BrandPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = IntraColors.SurfaceWhite
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = if (hasCounter) IntraColors.EmeraldDeep else IntraColors.WarningAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (hasCounter) "Изменить" else "Ввести",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasCounter) IntraColors.EmeraldDeep else IntraColors.WarningAmber,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvrSerialBanner(
    equipmentScan: EquipmentScanResult?,
    expectedSerial: String,
    isSaving: Boolean,
    onEditSerial: () -> Unit
) {
    val serial = equipmentScan?.serialNumber.orEmpty()
    val hasSerial = serial.isNotBlank()
    val isMismatch = hasSerial && equipmentScan?.matchesTicket == false

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isSaving, onClick = onEditSerial),
        shape = RoundedCornerShape(12.dp),
        color = when {
            isMismatch -> IntraColors.ErrorTint
            hasSerial -> IntraColors.EmeraldTint
            else -> IntraColors.WarningTint
        },
        border = BorderStroke(
            1.dp,
            when {
                isMismatch -> IntraColors.ErrorRed.copy(alpha = 0.55f)
                hasSerial -> IntraColors.EmeraldSuccess.copy(alpha = 0.45f)
                else -> IntraColors.WarningAmber.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (hasSerial) {
                    Text(
                        text = "Серийный номер: $serial",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isMismatch) IntraColors.ErrorRed else IntraColors.EmeraldDeep
                    )
                    val subtitle = when {
                        equipmentScan?.matchesTicket == true -> "Совпадает с заявкой"
                        equipmentScan?.matchesTicket == false ->
                            equipmentScan.matchNote.ifBlank { "Не совпадает с заявкой" }
                        equipmentScan?.rawSource == "Ручной ввод" -> "Указано инженером вручную"
                        else -> "Считан с шильдика / штрихкода"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMismatch) {
                            IntraColors.ErrorRed.copy(alpha = 0.9f)
                        } else {
                            IntraColors.EmeraldDeep.copy(alpha = 0.85f)
                        }
                    )
                } else {
                    Text(
                        text = "Серийный номер не указан",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = IntraColors.WarningAmber
                    )
                    Text(
                        text = if (expectedSerial.isNotBlank()) {
                            "По заявке: $expectedSerial - нажмите для ввода"
                        } else {
                            "Нажмите для ввода или сканирования"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = IntraColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = IntraColors.SurfaceWhite
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = when {
                            isMismatch -> IntraColors.ErrorRed
                            hasSerial -> IntraColors.EmeraldDeep
                            else -> IntraColors.WarningAmber
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (hasSerial) "Изменить" else "Ввести",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isMismatch -> IntraColors.ErrorRed
                            hasSerial -> IntraColors.EmeraldDeep
                            else -> IntraColors.WarningAmber
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    detail: TicketDetail,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "История (${detail.history.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = IntraColors.TextPrimary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = IntraColors.TextSecondary
                )
            }

            var showAllHistory by remember(detail.id) { mutableStateOf(false) }
            val visibleEvents = if (showAllHistory) detail.history else detail.history.take(25)

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    visibleEvents.forEach { ev ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = IntraColors.BackgroundIce
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ev.editorName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = IntraColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = ev.dateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = IntraColors.TextTertiary
                                    )
                                }

                                if (ev.statusName.isNotBlank()) {
                                    Text(
                                        text = "Статус: ${ev.statusName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (ev.statusName.contains("починил", ignoreCase = true)) {
                                            IntraColors.EmeraldDeep
                                        } else {
                                            IntraColors.BrandPrimary
                                        }
                                    )
                                }

                                if (ev.addedFiles.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Rounded.AddCircleOutline,
                                            contentDescription = null,
                                            tint = IntraColors.EmeraldDeep,
                                            modifier = Modifier
                                                .padding(top = 1.dp)
                                                .size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "Добавлены файлы: ${ev.addedFiles.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = IntraColors.EmeraldDeep
                                        )
                                    }
                                }

                                if (ev.removedFiles.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            tint = IntraColors.ErrorRed,
                                            modifier = Modifier
                                                .padding(top = 1.dp)
                                                .size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "Удалены файлы: ${ev.removedFiles.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = IntraColors.ErrorRed
                                        )
                                    }
                                }

                                ev.changes.forEach { changeLine ->
                                    Text(
                                        text = changeLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = IntraColors.TextPrimary
                                    )
                                }

                                if (ev.comment.isNotBlank()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = IntraColors.SurfaceWhite
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            if (ev.isPublic != null) {
                                                Text(
                                                    text = if (ev.isPublic) "Комментарий (публичный)" else "Комментарий (внутренний)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = IntraColors.TextTertiary
                                                )
                                            }
                                            Text(
                                                text = ev.comment,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = IntraColors.TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!showAllHistory && detail.history.size > 25) {
                        TextButton(
                            onClick = { showAllHistory = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Показать всю историю (${detail.history.size})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeferredOutboxCard(
    entry: PendingOutboxEntry,
    isSaving: Boolean,
    onRetryNow: () -> Unit,
    onCancelDeferred: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = IntraColors.WarningTint,
        border = BorderStroke(1.dp, IntraColors.WarningAmber.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = IntraColors.WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "В очереди отложенной отправки",
                    style = MaterialTheme.typography.titleSmall,
                    color = IntraColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Зафиксировано: ${entry.recordedAtText}",
                style = MaterialTheme.typography.labelMedium,
                color = IntraColors.WarningAmber,
                fontWeight = FontWeight.SemiBold
            )
            entry.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    text = "Причина задержки: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextSecondary
                )
            }
            Text(
                text = "Как только Интра станет доступна, данные и фото отправятся с зафиксированным временем. Если измените статус или файлы и нажмёте «Обновить очередь», данные перезапишутся на новые.",
                style = MaterialTheme.typography.bodySmall,
                color = IntraColors.TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRetryNow,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IntraColors.WarningAmber,
                        contentColor = Color.White
                    )
                ) {
                    Text("Отправить сейчас", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(
                    onClick = onCancelDeferred,
                    enabled = !isSaving
                ) {
                    Text("Отменить", color = IntraColors.ErrorRed)
                }
            }
        }
    }
}

@Composable
private fun SaveStickyBottomBar(
    state: TaskDetailUiState,
    onSaveClick: () -> Unit
) {
    val readiness = state.saveReadiness
    Surface(
        color = IntraColors.SurfaceWhite,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = state.actionMessage != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = IntraColors.ErrorTint,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.actionMessage.orEmpty(),
                        color = IntraColors.ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (!state.isSaving) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RequirementCheckPill(label = "АВР", satisfied = readiness.hasAvr)
                    RequirementCheckPill(label = "Тест-лист", satisfied = readiness.hasTestList)
                    RequirementCheckPill(
                        label = if (readiness.hasValidCounter) {
                            "Счётчик: ${AvrCounterParser.formatCounterDisplay(state.counterValue)}"
                        } else {
                            "Счётчик"
                        },
                        satisfied = readiness.hasValidCounter
                    )
                }
            }

            Button(
                onClick = onSaveClick,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.markAsRepaired) {
                        IntraColors.EmeraldSuccess
                    } else {
                        IntraColors.BrandPrimary
                    },
                    contentColor = Color.White,
                    disabledContainerColor = IntraColors.SubtleBorder,
                    disabledContentColor = IntraColors.TextTertiary
                )
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(state.savingStage.ifBlank { "Сохранение..." })
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.queuedOutbox != null) "Обновить очередь" else "Сохранить",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun RequirementCheckPill(
    label: String,
    satisfied: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (satisfied) IntraColors.EmeraldTint else IntraColors.WarningTint,
        border = BorderStroke(
            width = 1.dp,
            color = if (satisfied) {
                IntraColors.EmeraldSuccess.copy(alpha = 0.55f)
            } else {
                IntraColors.WarningAmber.copy(alpha = 0.55f)
            }
        ),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (satisfied) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (satisfied) IntraColors.EmeraldDeep else IntraColors.WarningAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (satisfied) IntraColors.EmeraldDeep else IntraColors.WarningAmber
            )
        }
    }
}

@Composable
private fun SerialNumberInputDialog(
    initialValue: String,
    expectedSerial: String,
    ticketText: String,
    onOpenScanner: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var inputText by remember(initialValue) {
        mutableStateOf(EquipmentScanParser.normalizePrinterSerial(initialValue))
    }
    val normalized = EquipmentScanParser.normalizePrinterSerial(inputText)
    val liveMatch = remember(normalized, ticketText) {
        EquipmentScanParser.buildManualResult(
            modelName = "",
            serialNumber = normalized,
            inventoryNumber = "",
            ticketText = ticketText,
            rawSource = "Ручной ввод"
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = IntraColors.SurfaceWhite,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Серийный номер (АВР)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = IntraColors.TextPrimary
                    )
                }

                Text(
                    text = "Укажите или отсканируйте серийный номер аппарата. Буквы O автоматически заменяются на 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextSecondary
                )

                if (expectedSerial.isNotBlank() && !normalized.equals(expectedSerial, ignoreCase = true)) {
                    OutlinedButton(
                        onClick = { inputText = EquipmentScanParser.normalizePrinterSerial(expectedSerial) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, IntraColors.BrandPrimary.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "Подставить из заявки: $expectedSerial",
                            style = MaterialTheme.typography.labelMedium,
                            color = IntraColors.BrandPrimary
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        inputText = EquipmentScanParser.normalizePrinterSerial(raw)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Серийный номер (S/N)") },
                    placeholder = { Text("Например: CNG9D4V0G3") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (normalized.isNotBlank()) onConfirm(normalized) }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IntraColors.BrandPrimary,
                        unfocusedBorderColor = IntraColors.SubtleBorder
                    )
                )

                if (normalized.isNotEmpty()) {
                    val previewText = when (liveMatch?.matchesTicket) {
                        true -> "Значение к отправке: $normalized (${liveMatch.matchNote})"
                        false -> liveMatch.matchNote
                        null -> "Значение к отправке: $normalized"
                    }
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (liveMatch?.matchesTicket == false) {
                            IntraColors.ErrorRed
                        } else {
                            IntraColors.EmeraldDeep
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onOpenScanner,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, IntraColors.BrandPrimary.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сканировать серийный номер",
                        color = IntraColors.BrandPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (initialValue.isNotBlank()) {
                        TextButton(onClick = onClear) {
                            Text("Очистить", color = IntraColors.ErrorRed)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = IntraColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(normalized) },
                        enabled = normalized.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IntraColors.BrandPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Применить")
                    }
                }
            }
        }
    }
}

@Composable
private fun AvrCounterCropDialog(
    photo: PendingPhoto,
    initialRoi: NormalizedRect?,
    isRecognizing: Boolean,
    onDismiss: () -> Unit,
    onRecognizeRegion: (NormalizedRect, Boolean) -> Unit
) {
    val imageAspectRatio = remember(photo.file.absolutePath) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.45f, 1.8f)
        } else {
            0.71f
        }
    }

    var roi by remember(photo.id, initialRoi) {
        mutableStateOf(
            initialRoi ?: NormalizedRect(
                left = 0.35f,
                top = 0.45f,
                right = 0.90f,
                bottom = 0.65f
            )
        )
    }
    var saveAsTemplate by remember { mutableStateOf(true) }
    var dragAnchor by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(22.dp),
            color = IntraColors.SurfaceWhite,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CropFree,
                            contentDescription = null,
                            tint = IntraColors.BrandPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Область счётчика на АВР",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = IntraColors.TextPrimary
                        )
                    }
                    if (!isRecognizing) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Закрыть",
                                tint = IntraColors.TextSecondary
                            )
                        }
                    }
                }

                Text(
                    text = "Проведите пальцем по фото АВР, чтобы выделить рамкой поле со счётчиком копий.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(imageAspectRatio)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .onSizeChanged { canvasSize = it }
                            .pointerInput(photo.id, canvasSize) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                                            dragAnchor = Offset(
                                                (start.x / canvasSize.width).coerceIn(0f, 1f),
                                                (start.y / canvasSize.height).coerceIn(0f, 1f)
                                            )
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val anchor = dragAnchor ?: return@detectDragGestures
                                        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@detectDragGestures
                                        change.consume()
                                        val curX = (change.position.x / canvasSize.width).coerceIn(0f, 1f)
                                        val curY = (change.position.y / canvasSize.height).coerceIn(0f, 1f)
                                        val l = minOf(anchor.x, curX)
                                        val r = maxOf(anchor.x, curX)
                                        val t = minOf(anchor.y, curY)
                                        val b = maxOf(anchor.y, curY)
                                        if (r - l >= 0.04f && b - t >= 0.03f) {
                                            roi = NormalizedRect(l, t, r, b)
                                        }
                                    },
                                    onDragEnd = { dragAnchor = null },
                                    onDragCancel = { dragAnchor = null }
                                )
                            }
                    ) {
                        AsyncImage(
                            model = photo.file,
                            contentDescription = "Фото АВР",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val leftPx = roi.left * size.width
                            val topPx = roi.top * size.height
                            val rightPx = roi.right * size.width
                            val bottomPx = roi.bottom * size.height
                            val boxW = (rightPx - leftPx).coerceAtLeast(4f)
                            val boxH = (bottomPx - topPx).coerceAtLeast(4f)

                            // Dim outside ROI
                            val shade = Color.Black.copy(alpha = 0.45f)
                            drawRect(shade, Offset.Zero, Size(size.width, topPx))
                            drawRect(shade, Offset(0f, bottomPx), Size(size.width, size.height - bottomPx))
                            drawRect(shade, Offset(0f, topPx), Size(leftPx, boxH))
                            drawRect(shade, Offset(rightPx, topPx), Size(size.width - rightPx, boxH))

                            // Selection border
                            drawRect(
                                color = Color(0xFF22C55E),
                                topLeft = Offset(leftPx, topPx),
                                size = Size(boxW, boxH),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isRecognizing) { saveAsTemplate = !saveAsTemplate },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = saveAsTemplate,
                        onCheckedChange = { if (!isRecognizing) saveAsTemplate = it }
                    )
                    Text(
                        text = "Запомнить шаблон АВР для следующих заявок",
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isRecognizing
                    ) {
                        Text("Отмена", color = IntraColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onRecognizeRegion(roi, saveAsTemplate) },
                        enabled = !isRecognizing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IntraColors.BrandPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        if (isRecognizing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Распознавание...")
                        } else {
                            Text("Распознать цифры")
                        }
                    }
                }
            }
        }
    }
}
