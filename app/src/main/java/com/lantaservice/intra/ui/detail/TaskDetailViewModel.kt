package com.lantaservice.intra.ui.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.ui.components.ViewerPhoto
import com.lantaservice.intra.util.EnhanceMode
import com.lantaservice.intra.util.ImageCompressor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val taskId: Int,
    val isLoading: Boolean = true,
    val detail: TicketDetail? = null,
    val loadError: String? = null,
    val markAsRepaired: Boolean = false,
    val commentText: String = "",
    val pendingPhotos: List<PendingPhoto> = emptyList(),
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

    private val _uiState = MutableStateFlow(TaskDetailUiState(taskId = taskId))
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var photoCounter = 0

    init {
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
                        markAsRepaired = if (fresh.canSetRepaired) cur.markAsRepaired else false
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
        if (target != null) {
            runCatching { target.file.delete() }
        }
        _uiState.update { state ->
            state.copy(pendingPhotos = state.pendingPhotos.filterNot { it.id == photoId })
        }
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
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSaving || !state.hasChangesToSave) return

        val targetStatusId = if (state.markAsRepaired) detail.repairedStatusId else null
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
                newStatusId = targetStatusId,
                pendingPhotos = state.pendingPhotos,
                comment = state.commentText,
                onStageChange = { stage ->
                    _uiState.update { it.copy(savingStage = stage) }
                }
            )
            result.onSuccess { saveResult ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        savingStage = "",
                        pendingPhotos = emptyList(),
                        markAsRepaired = false,
                        commentText = ""
                    )
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
        ImageCompressor.cleanupUploadFiles(_uiState.value.pendingPhotos)
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
