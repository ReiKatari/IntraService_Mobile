package com.lantaservice.intra.ui.detail

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.ui.components.ViewerPhoto
import com.lantaservice.intra.util.EnhanceMode
import com.lantaservice.intra.util.ImageCompressor
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
    val isProcessingPhoto: Boolean = false,
    val isSaving: Boolean = false,
    val savingStage: String = "",
    val actionMessage: String? = null,
    val isHistoryExpanded: Boolean = false,
    val viewerPhoto: ViewerPhoto? = null
) {
    val hasChangesToSave: Boolean
        get() = markAsRepaired || pendingPhotos.isNotEmpty() || commentText.isNotBlank()
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
            queuedOutbox = initialOutbox
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
    }

    fun nextPhotoIndex(): Int = ++photoCounter

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

    fun addPreparedPhoto(photo: PendingPhoto) {
        _uiState.update { state ->
            state.copy(
                pendingPhotos = state.pendingPhotos + photo,
                actionMessage = null
            )
        }
    }

    fun addCapturedPhoto(context: Context, sourceUri: Uri, tempFileToDelete: Boolean = false) {
        addPhotoUris(context, listOf(sourceUri), deleteSourceFiles = tempFileToDelete)
    }

    fun addPhotoUris(context: Context, uris: List<Uri>, deleteSourceFiles: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingPhoto = true, actionMessage = null) }
            val added = mutableListOf<PendingPhoto>()
            var lastError: String? = null

            for (uri in uris) {
                val idx = ++photoCounter
                runCatching {
                    ImageCompressor.compressPhoto(
                        context = context,
                        sourceUri = uri,
                        taskId = taskId,
                        index = idx,
                        enhanceMode = EnhanceMode.TEXT_CLARITY
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
        if (state.isSaving || !state.hasChangesToSave) return

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
                            pendingPhotos = refreshedPhotos
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
                            commentText = ""
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
                sharpnessScore = staged.sharpnessScore
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
