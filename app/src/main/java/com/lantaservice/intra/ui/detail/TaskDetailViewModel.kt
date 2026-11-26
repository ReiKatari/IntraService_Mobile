package com.lantaservice.intra.ui.detail

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.geo.GeoAddressSnapshot
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.PhotoDocumentType
import com.lantaservice.intra.data.model.PhotoDocumentTypeLock
import com.lantaservice.intra.data.model.SaveReadiness
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TaskSaveValidator
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.ui.components.ViewerPhoto
import com.lantaservice.intra.util.AvrCounterRecognizer
import com.lantaservice.intra.util.EnhanceMode
import com.lantaservice.intra.util.EquipmentScanParser
import com.lantaservice.intra.util.EquipmentScanResult
import com.lantaservice.intra.util.ImageCompressor
import com.lantaservice.intra.util.NormalizedRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@Immutable
data class TaskDetailUiState(
    val taskId: Int,
    val isLoading: Boolean = true,
    val detail: TicketDetail? = null,
    val loadError: String? = null,
    val markAsRepaired: Boolean = false,
    val commentText: String = "",
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val queuedOutbox: PendingOutboxEntry? = null,
    val geoSnapshot: GeoAddressSnapshot? = null,
    val isLoadingGeo: Boolean = false,
    val counterValue: String = "",
    val counterAutoDetected: Boolean = false,
    val isRecognizingCounter: Boolean = false,
    val isRecognizingRoiCrop: Boolean = false,
    val showManualCounterPrompt: Boolean = false,
    val counterPromptReason: String? = null,
    val equipmentScan: EquipmentScanResult? = null,
    val isProcessingPhoto: Boolean = false,
    val isSaving: Boolean = false,
    val savingStage: String = "",
    val actionMessage: String? = null,
    val isHistoryExpanded: Boolean = false,
    val viewerPhoto: ViewerPhoto? = null
) {
    val hasChangesToSave: Boolean
        get() = markAsRepaired ||
            pendingPhotos.isNotEmpty() ||
            commentText.isNotBlank() ||
            counterValue.isNotBlank() ||
            equipmentScan != null

    val saveReadiness: SaveReadiness
        get() = TaskSaveValidator.evaluate(
            pendingPhotos = pendingPhotos,
            existingAttachments = detail?.attachments.orEmpty(),
            counterValue = counterValue,
            hasChanges = hasChangesToSave
        )

    val canSave: Boolean
        get() = saveReadiness.isReadyToSave &&
            !isSaving &&
            !isProcessingPhoto &&
            !isRecognizingCounter &&
            !isRecognizingRoiCrop
}

class TaskDetailViewModel(
    private val taskId: Int,
    private val repository: IntraRepository
) : ViewModel() {

    private val initialDetail = repository.getCachedDetailPreview(taskId)
    private val initialOutbox = repository.getOutboxEntry(taskId)
    private val initialQueuedPhotos = initialOutbox?.let(::outboxEntryToPendingPhotos).orEmpty()

    private val _uiState = MutableStateFlow(
        TaskDetailUiState(
            taskId = taskId,
            isLoading = initialDetail == null,
            detail = initialDetail,
            markAsRepaired = initialOutbox?.newStatusId != null,
            commentText = initialOutbox?.comment.orEmpty(),
            pendingPhotos = initialQueuedPhotos,
            queuedOutbox = initialOutbox,
            geoSnapshot = initialOutbox?.geoSnapshot ?: repository.getCachedGeoSnapshot(),
            counterValue = initialOutbox?.counterValue.orEmpty(),
            counterAutoDetected = false,
            equipmentScan = initialOutbox?.equipmentScan
        )
    )
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var photoCounter = initialQueuedPhotos.size

    init {
        viewModelScope.launch {
            repository.outboxEntries.collect { entries ->
                val currentOutbox = entries.firstOrNull { it.taskId == taskId }
                val prevOutbox = _uiState.value.queuedOutbox
                if (prevOutbox != null && currentOutbox == null) {
                    // Background outbox sync succeeded while detail screen was open
                    _uiState.update { state ->
                        state.copy(
                            queuedOutbox = null,
                            markAsRepaired = false,
                            commentText = "",
                            counterValue = "",
                            counterAutoDetected = false,
                            equipmentScan = null,
                            pendingPhotos = emptyList(),
                            actionMessage = null
                        )
                    }
                    loadDetail()
                } else if (currentOutbox != prevOutbox) {
                    _uiState.update { it.copy(queuedOutbox = currentOutbox) }
                }
            }
        }
        loadDetail()
        refreshGeolocation(autoRetry = true)
    }

    fun nextPhotoIndex(): Int = ++photoCounter

    fun refreshGeolocation(
        exifHint: Pair<Double, Double>? = null,
        autoRetry: Boolean = false
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingGeo = true) }
            val onPrelim: suspend (GeoAddressSnapshot) -> Unit = { prelim ->
                _uiState.update { state ->
                    state.copy(geoSnapshot = prelim)
                }
            }
            var captured = repository.captureCurrentGeoProgressive(
                exifHint = exifHint,
                onPreliminary = onPrelim
            )
            if (captured == null && autoRetry) {
                val backoffMs = listOf(1200L)
                for (waitMs in backoffMs) {
                    delay(waitMs)
                    captured = repository.captureCurrentGeoProgressive(
                        exifHint = exifHint,
                        onPreliminary = onPrelim
                    )
                    if (captured != null) break
                }
            }
            _uiState.update { state ->
                state.copy(
                    geoSnapshot = captured ?: state.geoSnapshot,
                    isLoadingGeo = false
                )
            }
        }
    }

    fun loadDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.detail == null, loadError = null) }
            val result = repository.loadTicketDetail(taskId)
            result.onSuccess { detail ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        detail = detail,
                        loadError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadError = err.message ?: "Не удалось загрузить заявку"
                    )
                }
            }
        }
    }

    /**
     * Silent 1-minute refresh while preserving engineer's unsaved photos, status toggle, and comment.
     */
    fun silentAutoRefresh() {
        val state = _uiState.value
        if (state.isLoading || state.isSaving || state.isProcessingPhoto || state.detail == null) return
        viewModelScope.launch {
            repository.loadTicketDetail(taskId).onSuccess { fresh ->
                _uiState.update { cur ->
                    cur.copy(
                        detail = fresh,
                        markAsRepaired = if (fresh.canSetRepaired || cur.queuedOutbox != null) cur.markAsRepaired else false
                    )
                }
            }
        }
    }

    fun toggleMarkAsRepaired() {
        val detail = _uiState.value.detail ?: return
        if (!detail.canSetRepaired) return
        _uiState.update {
            it.copy(
                markAsRepaired = !it.markAsRepaired,
                actionMessage = null
            )
        }
    }

    fun onCommentChange(value: String) {
        _uiState.update { it.copy(commentText = value, actionMessage = null) }
    }

    fun toggleHistoryExpanded() {
        _uiState.update { it.copy(isHistoryExpanded = !it.isHistoryExpanded) }
    }

    fun openPhotoViewer(photo: ViewerPhoto) {
        _uiState.update { it.copy(viewerPhoto = photo) }
    }

    fun closePhotoViewer() {
        _uiState.update { it.copy(viewerPhoto = null) }
    }

    fun openManualCounterDialog() {
        _uiState.update {
            it.copy(
                showManualCounterPrompt = true,
                counterPromptReason = null,
                actionMessage = null
            )
        }
    }

    fun dismissManualCounterDialog() {
        _uiState.update {
            it.copy(
                showManualCounterPrompt = false,
                counterPromptReason = null
            )
        }
    }

    fun submitManualCounter(rawInput: String) {
        val normalized = TaskSaveValidator.normalizeCounterDigits(rawInput)
        if (!TaskSaveValidator.isValidCounter(normalized)) {
            _uiState.update {
                it.copy(
                    counterPromptReason = "Введите корректное целое число счётчика копий (от 1 до 9 цифр)"
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                counterValue = normalized,
                counterAutoDetected = false,
                showManualCounterPrompt = false,
                counterPromptReason = null,
                actionMessage = null
            )
        }
    }

    fun onEquipmentScanned(result: EquipmentScanResult) {
        _uiState.update {
            it.copy(
                equipmentScan = result,
                actionMessage = null
            )
        }
    }

    fun updateEquipmentManual(
        modelName: String,
        serialNumber: String,
        inventoryNumber: String,
        rawSource: String = "Ручной ввод"
    ) {
        val detail = _uiState.value.detail
        val ticketText = if (detail != null) "${detail.name}\n${detail.description}" else ""
        val built = EquipmentScanParser.buildManualResult(
            modelName = modelName,
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber,
            ticketText = ticketText,
            rawSource = rawSource
        )
        _uiState.update {
            it.copy(
                equipmentScan = built,
                actionMessage = null
            )
        }
    }

    fun clearEquipmentScan() {
        _uiState.update { it.copy(equipmentScan = null) }
    }

    fun recognizeCounterFromCrop(
        context: Context,
        photoFile: File,
        roi: NormalizedRect,
        saveTemplate: Boolean,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRecognizingRoiCrop = true, actionMessage = null) }
            if (saveTemplate) {
                AvrCounterRecognizer.saveAvrTemplateRoi(context, roi)
            }
            val rawDigits = runCatching {
                AvrCounterRecognizer.recognizeDigitsInCropRegion(context, photoFile, roi)
            }.getOrNull()

            val normalized = rawDigits
                ?.let { TaskSaveValidator.normalizeCounterDigits(it) }
                ?.takeIf { TaskSaveValidator.isValidCounter(it) }

            if (normalized != null) {
                _uiState.update {
                    it.copy(
                        isRecognizingRoiCrop = false,
                        counterValue = normalized,
                        counterAutoDetected = true,
                        showManualCounterPrompt = false,
                        counterPromptReason = null
                    )
                }
                onDone(true)
            } else {
                _uiState.update {
                    it.copy(
                        isRecognizingRoiCrop = false,
                        showManualCounterPrompt = true,
                        counterPromptReason = "В выделенной области не удалось прочитать цифры - введите вручную"
                    )
                }
                onDone(false)
            }
        }
    }

    fun addPreparedPhoto(context: Context, photo: PendingPhoto) {
        val stampedPhoto = photo.copy(createdAtEpochMs = System.currentTimeMillis())
        _uiState.update { state ->
            state.copy(
                pendingPhotos = state.pendingPhotos + stampedPhoto,
                actionMessage = null
            )
        }
        if (_uiState.value.geoSnapshot == null) {
            refreshGeolocation()
        }
        if (stampedPhoto.documentType == PhotoDocumentType.AVR) {
            recognizeAvrCounter(context, stampedPhoto.file)
        }
    }

    fun addCapturedPhoto(
        context: Context,
        sourceUri: Uri,
        documentType: PhotoDocumentType,
        tempFileToDelete: Boolean = false
    ) {
        addPhotoUris(
            context = context,
            uris = listOf(sourceUri),
            documentType = documentType,
            deleteSourceFiles = tempFileToDelete
        )
    }

    fun addPhotoUris(
        context: Context,
        uris: List<Uri>,
        documentType: PhotoDocumentType,
        deleteSourceFiles: Boolean = false
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingPhoto = true, actionMessage = null) }
            val added = mutableListOf<PendingPhoto>()
            var lastError: String? = null
            var exifHint: Pair<Double, Double>? = null

            for (uri in uris) {
                if (exifHint == null) {
                    exifHint = ImageCompressor.readExifLatLong(context, uri)
                }
                val idx = ++photoCounter
                runCatching {
                    ImageCompressor.compressPhoto(
                        context = context,
                        sourceUri = uri,
                        taskId = taskId,
                        index = idx,
                        enhanceMode = EnhanceMode.TEXT_CLARITY,
                        documentType = documentType
                    )
                }.onSuccess { compressed ->
                    added += compressed
                }.onFailure { err ->
                    lastError = err.message ?: "Не удалось обработать фото"
                }

                if (deleteSourceFiles) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }

            _uiState.update { state ->
                state.copy(
                    isProcessingPhoto = false,
                    pendingPhotos = state.pendingPhotos + added,
                    actionMessage = lastError
                )
            }

            val currentGeo = _uiState.value.geoSnapshot
            if (currentGeo == null || (exifHint != null && currentGeo.jammerSuspected)) {
                refreshGeolocation(exifHint = exifHint)
            }

            if (documentType == PhotoDocumentType.AVR && added.isNotEmpty()) {
                recognizeAvrCounter(context, added.last().file)
            }
        }
    }

    fun changePhotoDocumentType(
        context: Context,
        photoId: String,
        newType: PhotoDocumentType
    ) {
        val current = _uiState.value.pendingPhotos
        val target = current.firstOrNull { it.id == photoId } ?: return
        if (!PhotoDocumentTypeLock.canChangeType(target.createdAtEpochMs, System.currentTimeMillis())) {
            _uiState.update {
                it.copy(actionMessage = "Формат фото уже зафиксирован (прошло более 5 минут после создания)")
            }
            return
        }
        if (target.documentType == newType) return

        val updatedPhoto = target.copy(
            documentType = newType,
            nameWithoutExt = PhotoDocumentTypeLock.renamePrefix(target.nameWithoutExt, newType)
        )
        _uiState.update { state ->
            state.copy(
                pendingPhotos = state.pendingPhotos.map { if (it.id == photoId) updatedPhoto else it },
                actionMessage = null
            )
        }
        if (newType == PhotoDocumentType.AVR) {
            recognizeAvrCounter(context, updatedPhoto.file)
        }
    }

    private fun recognizeAvrCounter(context: Context, imageFile: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRecognizingCounter = true) }
            val result = runCatching {
                AvrCounterRecognizer.recognizeCounterFromFile(context, imageFile)
            }.getOrNull()

            val recognizedDigits = if (result?.confidence == com.lantaservice.intra.util.CounterConfidence.HIGH) {
                result.value
                    ?.let { TaskSaveValidator.normalizeCounterDigits(it) }
                    ?.takeIf { TaskSaveValidator.isValidCounter(it) }
            } else {
                null
            }

            if (recognizedDigits != null) {
                _uiState.update { state ->
                    state.copy(
                        isRecognizingCounter = false,
                        counterValue = recognizedDigits,
                        counterAutoDetected = true,
                        showManualCounterPrompt = false,
                        counterPromptReason = null,
                        actionMessage = null
                    )
                }
            } else {
                _uiState.update { state ->
                    val shouldPrompt = state.counterValue.isBlank()
                    state.copy(
                        isRecognizingCounter = false,
                        showManualCounterPrompt = shouldPrompt,
                        counterPromptReason = if (shouldPrompt) {
                            "Не удалось автоматически распознать счётчик копий на фото АВР (плохое качество или почерк). Пожалуйста, введите счётчик вручную."
                        } else {
                            state.counterPromptReason
                        }
                    )
                }
            }
        }
    }

    fun removePendingPhoto(photoId: String) {
        val current = _uiState.value.pendingPhotos
        val target = current.firstOrNull { it.id == photoId }
        if (target != null && repository.isTemporaryPhotoFile(target.file)) {
            runCatching { target.file.delete() }
        }
        _uiState.update { state ->
            state.copy(pendingPhotos = state.pendingPhotos.filterNot { it.id == photoId })
        }
    }

    fun cancelDeferredSend() {
        repository.cancelOutboxEntry(taskId)
        repository.cleanupTemporaryPhotos(_uiState.value.pendingPhotos)
        _uiState.update {
            it.copy(
                queuedOutbox = null,
                markAsRepaired = false,
                commentText = "",
                counterValue = "",
                counterAutoDetected = false,
                equipmentScan = null,
                pendingPhotos = emptyList(),
                actionMessage = null
            )
        }
    }

    fun retryDeferredSendNow(onSuccess: (TicketSaveResult) -> Unit) {
        val entry = _uiState.value.queuedOutbox ?: return
        submitInternal(
            forcedRecordedAtText = entry.recordedAtText,
            onSuccess = onSuccess
        )
    }

    fun downloadAndOpenAttachment(
        attachment: TaskAttachment,
        onUriReady: (Uri, String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionMessage = null) }
            val result = repository.downloadAttachmentForViewing(attachment)
            result.onSuccess { uri ->
                onUriReady(uri, attachment.name)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(actionMessage = err.message ?: "Не удалось открыть файл")
                }
            }
        }
    }

    fun saveChanges(onSuccess: (TicketSaveResult) -> Unit) {
        submitInternal(forcedRecordedAtText = null, onSuccess = onSuccess)
    }

    private fun submitInternal(
        forcedRecordedAtText: String?,
        onSuccess: (TicketSaveResult) -> Unit
    ) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSaving) return

        val readiness = state.saveReadiness
        if (!readiness.isReadyToSave) {
            val missing = readiness.missingItems()
            val msg = if (missing.isNotEmpty()) {
                "Для сохранения обязательно приложите: ${missing.joinToString(", ")}"
            } else {
                "Нет изменений для сохранения"
            }
            _uiState.update { it.copy(actionMessage = msg) }
            return
        }

        val targetStatusId = if (state.markAsRepaired) {
            detail.repairedStatusId ?: state.queuedOutbox?.newStatusId
        } else {
            null
        }
        if (state.markAsRepaired && targetStatusId == null) {
            _uiState.update { it.copy(actionMessage = "Не найден целевой статус «Починил»") }
            return
        }
        if (state.markAsRepaired && detail.isCommentRequiredForRepaired && state.commentText.isBlank()) {
            _uiState.update {
                it.copy(actionMessage = "Для перевода в статус «Починил» необходимо заполнить комментарий")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    savingStage = "Подготовка...",
                    actionMessage = null
                )
            }
            val result = repository.saveTicketChanges(
                taskId = taskId,
                taskName = detail.name,
                newStatusId = targetStatusId,
                newStatusName = if (state.markAsRepaired) detail.repairedStatusName else null,
                pendingPhotos = state.pendingPhotos,
                comment = state.commentText,
                counterValue = state.counterValue,
                geoSnapshot = state.geoSnapshot,
                equipmentScan = state.equipmentScan,
                allowOfflineQueue = true,
                forcedRecordedAtText = forcedRecordedAtText,
                onStageChange = { stage ->
                    _uiState.update { it.copy(savingStage = stage) }
                }
            )
            result.onSuccess { saveResult ->
                if (saveResult.deferred) {
                    val freshOutbox = repository.getOutboxEntry(taskId)
                    val refreshedPhotos = freshOutbox?.let(::outboxEntryToPendingPhotos).orEmpty()
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            savingStage = "",
                            queuedOutbox = freshOutbox,
                            pendingPhotos = refreshedPhotos,
                            counterValue = freshOutbox?.counterValue ?: it.counterValue,
                            geoSnapshot = freshOutbox?.geoSnapshot ?: it.geoSnapshot,
                            equipmentScan = freshOutbox?.equipmentScan ?: it.equipmentScan
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            savingStage = "",
                            queuedOutbox = null,
                            pendingPhotos = emptyList(),
                            markAsRepaired = false,
                            commentText = "",
                            counterValue = "",
                            counterAutoDetected = false,
                            equipmentScan = null
                        )
                    }
                }
                onSuccess(saveResult)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        savingStage = "",
                        actionMessage = err.message ?: "Ошибка при сохранении заявки"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanupTemporaryPhotos(_uiState.value.pendingPhotos)
    }

    private fun outboxEntryToPendingPhotos(entry: PendingOutboxEntry): List<PendingPhoto> {
        val fallbackEpoch = entry.recordedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        return entry.photos.mapNotNull { staged ->
            val file = File(staged.filePath)
            if (!file.exists()) null
            else PendingPhoto(
                id = staged.id,
                file = file,
                nameWithoutExt = staged.nameWithoutExt,
                sizeBytes = file.length(),
                isEnhanced = staged.isEnhanced,
                isBlurryWarning = staged.isBlurryWarning,
                sharpnessScore = staged.sharpnessScore,
                documentType = staged.documentType,
                createdAtEpochMs = staged.createdAtEpochMs.takeIf { it > 0L } ?: fallbackEpoch
            )
        }
    }

    class Factory(
        private val taskId: Int,
        private val repository: IntraRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TaskDetailViewModel(taskId, repository) as T
        }
    }
}
