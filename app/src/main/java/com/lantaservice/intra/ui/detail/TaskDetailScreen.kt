package com.lantaservice.intra.ui.detail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.ui.camera.SmartCameraScreen
import com.lantaservice.intra.ui.components.FullscreenPhotoViewer
import com.lantaservice.intra.ui.components.IntraHeaderBanner
import com.lantaservice.intra.ui.components.intraHeaderInsetsPadding
import com.lantaservice.intra.ui.components.StatusPill
import com.lantaservice.intra.ui.components.ViewerPhoto
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.ImageCompressor
import kotlinx.coroutines.delay

@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onSavedSuccess: (TicketSaveResult) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    var showSmartCamera by remember { mutableStateOf(false) }
    var smartCameraPhotoIndex by remember { mutableIntStateOf(1) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

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
            viewModel.addCapturedPhoto(context, Uri.parse(uriStr), tempFileToDelete = true)
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
            smartCameraPhotoIndex = viewModel.nextPhotoIndex()
            showSmartCamera = true
        }
    }

    fun onCapturePhotoClick() {
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

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPhotoUris(context, uris)
        }
    }

    if (showSmartCamera) {
        SmartCameraScreen(
            taskId = state.taskId,
            photoIndex = smartCameraPhotoIndex,
            onDismiss = { showSmartCamera = false },
            onPhotoAccepted = { photo ->
                showSmartCamera = false
                viewModel.addPreparedPhoto(photo)
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
                        .padding(horizontal = 10.dp, vertical = 10.dp),
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
                        onClick = viewModel::loadDetail,
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
                                isProcessingPhoto = state.isProcessingPhoto,
                                isSaving = state.isSaving,
                                onTakePhoto = ::onCapturePhotoClick,
                                onPickFromGallery = {
                                    pickGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onRemovePending = viewModel::removePendingPhoto,
                                onPreviewPending = { pending ->
                                    viewModel.openPhotoViewer(
                                        ViewerPhoto(
                                            title = "${pending.nameWithoutExt}.jpg",
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
                            Spacer(modifier = Modifier.height(12.dp))
                        }
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Телефон",
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextTertiary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
    isProcessingPhoto: Boolean,
    isSaving: Boolean,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
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
                        text = "Вложенные файлы",
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
                        text = "Повышение четкости и сжатие фото...",
                        style = MaterialTheme.typography.bodySmall,
                        color = IntraColors.TextSecondary
                    )
                }
            }

            if (pendingPhotos.isNotEmpty()) {
                Text(
                    text = "Новые фото к отправке (${pendingPhotos.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = IntraColors.EmeraldDeep,
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pendingPhotos, key = { it.id }) { pending ->
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(IntraColors.BackgroundIce)
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
                    }
                }
            }

            if (detail.attachments.isNotEmpty()) {
                Text(
                    text = "Уже прикреплено (${detail.attachments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = IntraColors.TextSecondary
                )
                val images = detail.attachments.filter { it.isImage }
                val files = detail.attachments.filter { !it.isImage }

                if (images.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(images, key = { it.id }) { img ->
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
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

                files.forEach { fileAtt ->
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
            } else if (pendingPhotos.isEmpty()) {
                Text(
                    text = "Файлы пока не прикреплены",
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextTertiary
                )
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

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    detail.history.take(15).forEach { ev ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = IntraColors.BackgroundIce
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                        color = if (ev.statusName.contains("починил", ignoreCase = true)) {
                                            IntraColors.EmeraldDeep
                                        } else {
                                            IntraColors.BrandPrimary
                                        }
                                    )
                                }
                                if (ev.comment.isNotBlank()) {
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

            if (state.hasChangesToSave && !state.isSaving) {
                val summaryParts = buildList {
                    if (state.markAsRepaired) add("Статус: Починил")
                    if (state.pendingPhotos.isNotEmpty()) add("Фото: ${state.pendingPhotos.size}")
                    if (state.commentText.isNotBlank()) add("Комментарий")
                }
                Text(
                    text = "К отправке: ${summaryParts.joinToString(" + ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = IntraColors.EmeraldDeep
                )
            }

            Button(
                onClick = onSaveClick,
                enabled = state.hasChangesToSave && !state.isSaving && !state.isProcessingPhoto,
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
                    contentColor = Color.White
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
