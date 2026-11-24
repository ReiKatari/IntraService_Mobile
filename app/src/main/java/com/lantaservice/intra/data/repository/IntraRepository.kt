package com.lantaservice.intra.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.lantaservice.intra.data.model.CurrentUserDto
import com.lantaservice.intra.data.model.IntraErrorDto
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TaskFormDto
import com.lantaservice.intra.data.model.TaskPriorityDto
import com.lantaservice.intra.data.model.TaskStatusDto
import com.lantaservice.intra.data.model.TaskStatusSimpleViewDto
import com.lantaservice.intra.data.model.TaskSummaryDto
import com.lantaservice.intra.data.model.TicketDetail
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.data.model.TicketLifetimeEvent
import com.lantaservice.intra.data.model.TicketSaveResult
import com.lantaservice.intra.data.model.UpdateTaskRequestDto
import com.lantaservice.intra.data.model.UserSession
import com.lantaservice.intra.data.model.extractFileTokens
import com.lantaservice.intra.data.network.IntraApiService
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.network.VpnBlockedConnectionException
import com.lantaservice.intra.data.storage.SavedCredentials
import com.lantaservice.intra.data.storage.SecureSessionStorage
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.ImageCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

class IntraNetworkException(
    message: String,
    val isVpnBlocked: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

sealed interface AuthState {
    data object Checking : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val session: UserSession) : AuthState
}

data class TicketListSnapshot(
    val tickets: List<TicketItem>,
    val isResolvingHistory: Boolean
)

/**
 * Central repository enforcing authenticated-user-only API calls against the locked
 * IntraService server, email + login authentication, and ticket status classification.
 */
class IntraRepository(
    private val context: Context,
    private val api: IntraApiService,
    private val json: Json,
    private val credentialsProvider: SessionCredentialsProvider,
    private val connectionManager: IntraConnectionManager,
    private val sessionStorage: SecureSessionStorage
) {

    // Always require explicit login screen when app process starts cleanly
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val unauthorizedEvents: Flow<Unit> = credentialsProvider.unauthorizedEvents

    private val statusesById = ConcurrentHashMap<Int, TaskStatusDto>()
    private val repairedHistoryCache = ConcurrentHashMap<String, Boolean>()
    private val locallyRepairedTaskIds = ConcurrentHashMap.newKeySet<Int>()

    fun serverDisplayHost(): String = SessionCredentialsProvider.FIXED_BASE_URL
        .removePrefix("https://")
        .removePrefix("http://")

    fun loadPrefilledCredentials(): SavedCredentials? = sessionStorage.load()

    fun clearInMemorySession() {
        credentialsProvider.clearSession()
        statusesById.clear()
        repairedHistoryCache.clear()
        locallyRepairedTaskIds.clear()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * Clears session and saved credentials immediately before Activity finishes and closes app.
     */
    fun logoutImmediate() {
        credentialsProvider.clearSession()
        sessionStorage.clear()
        statusesById.clear()
        repairedHistoryCache.clear()
        locallyRepairedTaskIds.clear()
        CoroutineScope(Dispatchers.IO).launch {
            ImageCompressor.cleanupCacheDirs(context)
        }
    }

    fun handleUnauthorized() {
        credentialsProvider.clearSession()
        sessionStorage.clear()
        statusesById.clear()
        repairedHistoryCache.clear()
        locallyRepairedTaskIds.clear()
        _authState.value = AuthState.Unauthenticated
    }

    suspend fun login(
        login: String,
        password: String,
        rememberMe: Boolean
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        try {
            val cleanInput = login.trim()
            if (cleanInput.isEmpty() || password.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Введите логин или E-mail и пароль"))
            }

            val fixedUrl = SessionCredentialsProvider.FIXED_BASE_URL

            // Stage 1: Direct Basic Auth with user input
            val directHeader = SessionCredentialsProvider.buildBasicAuthHeader(cleanInput, password)
            val directResp = api.getCurrentUserInfo(
                getCurrentUserInfo = true,
                explicitAuthHeader = directHeader
            )
            if (directResp.isSuccessful) {
                val user = directResp.body()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Сервер вернул пустой профиль пользователя")
                    )
                return@withContext completeLoginSession(
                    user = user,
                    authLogin = user.login?.takeIf { it.isNotBlank() } ?: cleanInput,
                    storedLoginInput = cleanInput,
                    password = password,
                    cookieHeader = null,
                    fixedUrl = fixedUrl,
                    rememberMe = rememberMe
                )
            }

            if (directResp.code() !in setOf(401, 403)) {
                return@withContext Result.failure(IllegalStateException(parseErrorMessage(directResp)))
            }

            // Stage 2: If email entered (e.g. p.stasyuk@lantaservice.com), try Basic Auth with local-part
            val localPart = extractEmailLocalPart(cleanInput)
            if (!localPart.isNullOrBlank()) {
                val localHeader = SessionCredentialsProvider.buildBasicAuthHeader(localPart, password)
                val localResp = api.getCurrentUserInfo(
                    getCurrentUserInfo = true,
                    explicitAuthHeader = localHeader
                )
                if (localResp.isSuccessful) {
                    val user = localResp.body()
                    val emailMatches = user != null && (
                        user.email.isNullOrBlank() ||
                            user.email.trim().equals(cleanInput, ignoreCase = true)
                        )
                    if (user != null && emailMatches) {
                        return@withContext completeLoginSession(
                            user = user,
                            authLogin = user.login?.takeIf { it.isNotBlank() } ?: localPart,
                            storedLoginInput = cleanInput,
                            password = password,
                            cookieHeader = null,
                            fixedUrl = fixedUrl,
                            rememberMe = rememberMe
                        )
                    }
                }
            }

            // Stage 3: Web-form login resolving any email / domain login on server -> fetch user profile
            val cookieHeader = connectionManager.authenticateViaWebForm(cleanInput, password)
            if (!cookieHeader.isNullOrBlank()) {
                connectionManager.fetchCurrentUserWithCookie(cookieHeader).use { userResp ->
                    if (userResp.isSuccessful) {
                        val bodyText = userResp.body?.string().orEmpty()
                        val user = runCatching {
                            json.decodeFromString(CurrentUserDto.serializer(), bodyText)
                        }.getOrNull()
                        if (user != null) {
                            val resolvedLogin = user.login?.trim().orEmpty()
                            if (resolvedLogin.isNotEmpty()) {
                                val verifyBasic = api.getCurrentUserInfo(
                                    getCurrentUserInfo = true,
                                    explicitAuthHeader = SessionCredentialsProvider.buildBasicAuthHeader(
                                        resolvedLogin,
                                        password
                                    )
                                )
                                if (verifyBasic.isSuccessful) {
                                    return@withContext completeLoginSession(
                                        user = user,
                                        authLogin = resolvedLogin,
                                        storedLoginInput = cleanInput,
                                        password = password,
                                        cookieHeader = null,
                                        fixedUrl = fixedUrl,
                                        rememberMe = rememberMe
                                    )
                                }
                            }
                            return@withContext completeLoginSession(
                                user = user,
                                authLogin = resolvedLogin.ifBlank { cleanInput },
                                storedLoginInput = cleanInput,
                                password = password,
                                cookieHeader = cookieHeader,
                                fixedUrl = fixedUrl,
                                rememberMe = rememberMe
                            )
                        }
                    }
                }
            }

            Result.failure(IllegalStateException("Неверный логин, E-mail или пароль"))
        } catch (t: Throwable) {
            Result.failure(toIntraException(t))
        }
    }

    private fun completeLoginSession(
        user: CurrentUserDto,
        authLogin: String,
        storedLoginInput: String,
        password: String,
        cookieHeader: String?,
        fixedUrl: String,
        rememberMe: Boolean
    ): Result<UserSession> {
        if (user.isArchive == true) {
            return Result.failure(IllegalStateException("Учетная запись находится в архиве"))
        }

        val session = UserSession(
            baseUrl = fixedUrl,
            login = authLogin,
            password = password,
            authCookieHeader = cookieHeader,
            userId = user.id,
            displayName = user.name?.takeIf { it.isNotBlank() } ?: authLogin,
            email = user.email,
            phone = user.mobilePhone?.takeIf { it.isNotBlank() } ?: user.phone,
            position = user.position,
            roleType = user.roleType
        )

        credentialsProvider.setSession(session)
        sessionStorage.save(
            SavedCredentials(
                baseUrl = fixedUrl,
                login = storedLoginInput,
                password = password,
                rememberMe = rememberMe
            )
        )
        _authState.value = AuthState.Authenticated(session)
        return Result.success(session)
    }

    suspend fun loadOpenTicketsProgressive(
        onProgress: suspend (TicketListSnapshot) -> Unit
    ): Result<TicketListSnapshot> = withContext(Dispatchers.IO) {
        try {
            val session = requireAuthenticatedSession()
            val statuses = refreshStatusCatalog()
            val repairedIds = findRepairedStatusIds(statuses)
            val openStatusIdsCsv = statuses
                .filter { !it.isFinal }
                .takeIf { it.isNotEmpty() && it.size <= 80 }
                ?.joinToString(",") { it.id.toString() }

            val collectedTasks = mutableListOf<TaskSummaryDto>()
            val mergedStatuses = LinkedHashMap<Int, TaskStatusDto>().apply {
                statuses.forEach { put(it.id, it) }
            }
            val mergedPriorities = LinkedHashMap<Int, TaskPriorityDto>()
            val mergedServices = LinkedHashMap<Int, String>()

            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 10) {
                val resp = api.getTasks(
                    executorIds = session.userId.toString(),
                    statusIds = openStatusIdsCsv,
                    pageSize = 200,
                    page = page
                )
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseErrorMessage(resp))
                }
                val body = resp.body() ?: break
                collectedTasks += body.tasks
                body.statuses.forEach { mergedStatuses[it.id] = it }
                body.priorities.forEach { mergedPriorities[it.id] = it }
                body.services.forEach { mergedServices[it.id] = it.name }
                totalPages = body.paginator?.pageCount?.coerceAtLeast(1) ?: 1
                if (body.tasks.isEmpty()) break
                page++
            }

            statusesById.putAll(mergedStatuses)
            val allRepairedIds = findRepairedStatusIds(mergedStatuses.values) + repairedIds

            val openTasks = collectedTasks.distinctBy { it.id }.filter { task ->
                val status = task.statusId?.let { mergedStatuses[it] }
                val isFinal = (status?.isFinal == true) || (task.statusIsFinal == true)
                val isClosed = !task.closed.isNullOrBlank()
                !isFinal && !isClosed
            }

            val initialItems = openTasks.map { task ->
                val status = task.statusId?.let { mergedStatuses[it] }
                val statusName = task.statusName?.takeIf { it.isNotBlank() }
                    ?: status?.name
                    ?: "Без статуса"
                val currentlyRepaired = isRepairedStatus(task.statusId, statusName, allRepairedIds)
                val cacheKey = lifetimeCacheKey(task.id, task.changed)
                val cachedHadRepaired = repairedHistoryCache[cacheKey]
                val hadRepaired = currentlyRepaired ||
                    (cachedHadRepaired == true) ||
                    locallyRepairedTaskIds.contains(task.id)

                val fileCount = DateAndTextFormatter.splitIds(task.fileIds).size

                TicketItem(
                    id = task.id,
                    name = task.name?.takeIf { it.isNotBlank() } ?: "Заявка #${task.id}",
                    descriptionPreview = DateAndTextFormatter.cleanHtmlText(task.description),
                    statusId = task.statusId,
                    statusName = statusName,
                    priorityId = task.priorityId,
                    priorityName = task.priorityName?.takeIf { it.isNotBlank() }
                        ?: task.priorityId?.let { mergedPriorities[it]?.name }
                        ?: "",
                    serviceName = task.serviceName?.takeIf { it.isNotBlank() }
                        ?: task.serviceId?.let { mergedServices[it] }
                        ?: "",
                    creatorName = task.creator?.takeIf { it.isNotBlank() } ?: "",
                    createdText = DateAndTextFormatter.formatIntraDate(task.created),
                    changedText = DateAndTextFormatter.formatIntraDate(task.changed),
                    changedRaw = task.changed.orEmpty(),
                    deadlineText = DateAndTextFormatter.formatDeadline(task.deadline),
                    isOverdue = (task.resolutionOverdue == true),
                    attachmentCount = fileCount,
                    hasHadRepairedStatus = hadRepaired,
                    isLifetimeChecked = currentlyRepaired || cachedHadRepaired != null
                )
            }

            val needHistoryCheck = initialItems.filter { !it.isLifetimeChecked }
            if (needHistoryCheck.isEmpty()) {
                val finalSnap = TicketListSnapshot(initialItems, isResolvingHistory = false)
                onProgress(finalSnap)
                return@withContext Result.success(finalSnap)
            }

            onProgress(TicketListSnapshot(initialItems, isResolvingHistory = true))

            val semaphore = Semaphore(6)
            val resolvedMap = ConcurrentHashMap<Int, Boolean>()

            coroutineScope {
                needHistoryCheck.map { item ->
                    async {
                        semaphore.withPermit {
                            val hadRepaired = checkTaskHadRepairedInLifetime(item.id, allRepairedIds)
                            if (hadRepaired != null) {
                                repairedHistoryCache[lifetimeCacheKey(item.id, item.changedRaw)] = hadRepaired
                                resolvedMap[item.id] = hadRepaired
                            }
                        }
                    }
                }.awaitAll()
            }

            val updatedItems = initialItems.map { item ->
                val resolved = resolvedMap[item.id]
                if (resolved == null) {
                    item.copy(isLifetimeChecked = true)
                } else {
                    item.copy(
                        hasHadRepairedStatus = item.hasHadRepairedStatus || resolved,
                        isLifetimeChecked = true
                    )
                }
            }
            val finalSnap = TicketListSnapshot(updatedItems, isResolvingHistory = false)
            onProgress(finalSnap)
            Result.success(finalSnap)
        } catch (t: Throwable) {
            Result.failure(toIntraException(t))
        }
    }

    suspend fun loadTicketDetail(taskId: Int): Result<TicketDetail> = withContext(Dispatchers.IO) {
        try {
            val session = requireAuthenticatedSession()
            if (statusesById.isEmpty()) {
                refreshStatusCatalog()
            }

            val (detailResp, lifetimeResp) = coroutineScope {
                val d = async { api.getTaskDetail(taskId) }
                val l = async {
                    runCatching {
                        api.getTaskLifetime(
                            taskId = taskId,
                            include = "status",
                            lastCommentsOnTop = true,
                            pageSize = 50,
                            page = 1
                        )
                    }.getOrNull()
                }
                d.await() to l.await()
            }

            if (!detailResp.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(detailResp))
            }
            val rawJson = detailResp.body()
                ?: throw IllegalStateException("Сервер не вернул данные заявки")

            val form = parseTaskForm(rawJson, json)
            val task = form.task
                ?: throw IllegalStateException("Заявка #$taskId не найдена")

            form.statuses.forEach { statusesById[it.id] = it }
            lifetimeResp?.body()?.statuses?.forEach { statusesById[it.id] = it }

            val allStatuses = statusesById.values.toList()
            val allRepairedIds = findRepairedStatusIds(allStatuses)

            val currentStatusObj = task.statusId?.let { statusesById[it] }
                ?: form.statuses.firstOrNull { it.id == task.statusId }
            val currentStatusName = task.statusName?.takeIf { it.isNotBlank() }
                ?: currentStatusObj?.name
                ?: "Без статуса"

            val isCurrentlyRepaired = isRepairedStatus(task.statusId, currentStatusName, allRepairedIds)

            val lifetimes = lifetimeResp?.body()?.taskLifetimes.orEmpty()
            val hadRepairedInHistory = lifetimes.any { ev ->
                val evName = ev.statusId?.let { statusesById[it]?.name }
                isRepairedStatus(ev.statusId, evName, allRepairedIds)
            }

            val hasHadRepaired = isCurrentlyRepaired ||
                hadRepairedInHistory ||
                locallyRepairedTaskIds.contains(taskId)

            if (hadRepairedInHistory || isCurrentlyRepaired) {
                repairedHistoryCache[lifetimeCacheKey(task.id, task.changed.orEmpty())] = true
            }

            val rights = form.userTaskRights
            val toStatuses = rights?.toStatuses.orEmpty()
            val targetFromRights = findBestRepairedTarget(toStatuses)
            val targetFromCatalog = allStatuses
                .firstOrNull { it.name.trim().equals(REPAIRED_STATUS_NAME, ignoreCase = true) }
                ?: allStatuses.firstOrNull { it.name.contains(REPAIRED_STATUS_KEYWORD, ignoreCase = true) }

            val repairedTargetId = targetFromRights?.id ?: targetFromCatalog?.id
            val repairedTargetName = targetFromRights?.name?.takeIf { it.isNotBlank() }
                ?: targetFromCatalog?.name?.takeIf { it.isNotBlank() }
                ?: REPAIRED_STATUS_NAME

            val statusEditable = rights == null || (rights.status and 1) != 0
            val (canSetRepaired, unavailableReason) = when {
                isCurrentlyRepaired -> false to null
                repairedTargetId == null -> false to "В системе не найден статус «Починил»"
                !statusEditable -> false to "У текущего пользователя нет прав на смену статуса"
                rights != null && toStatuses.isNotEmpty() && targetFromRights == null ->
                    false to "Перевод в статус «Починил» недоступен из текущего статуса «$currentStatusName»"
                else -> true to null
            }

            val isCommentRequired = repairedTargetId?.let { statusesById[it]?.isCommentRequired } ?: false
            val canAttachFiles = rights == null || (rights.files and 1) != 0
            val canComment = rights == null || (rights.comment and 1) != 0 || rights.comment == 0

            val attachments = DateAndTextFormatter.parseAttachments(
                fileIdsRaw = task.fileIds,
                fileNamesRaw = task.fileNames,
                legacyFilesRaw = task.files,
                baseUrl = session.baseUrl
            )

            val historyEvents = lifetimes.map { ev ->
                val stName = ev.statusId?.let { statusesById[it]?.name }.orEmpty()
                TicketLifetimeEvent(
                    dateText = DateAndTextFormatter.formatIntraDate(ev.date),
                    editorName = ev.editor?.takeIf { it.isNotBlank() } ?: "Система",
                    statusId = ev.statusId,
                    statusName = stName,
                    comment = DateAndTextFormatter.cleanHtmlText(ev.comments)
                )
            }

            val priorityName = task.priorityName?.takeIf { it.isNotBlank() }
                ?: form.priorities.firstOrNull { it.id == task.priorityId }?.name
                ?: ""
            val serviceName = task.serviceName?.takeIf { it.isNotBlank() }
                ?: form.services.firstOrNull { it.id == task.serviceId }?.name
                ?: ""

            Result.success(
                TicketDetail(
                    id = task.id,
                    name = task.name?.takeIf { it.isNotBlank() } ?: "Заявка #${task.id}",
                    description = DateAndTextFormatter.cleanHtmlText(task.description)
                        .ifBlank { "Описание не указано" },
                    statusId = task.statusId,
                    statusName = currentStatusName,
                    priorityName = priorityName,
                    serviceName = serviceName,
                    creatorName = task.creator?.takeIf { it.isNotBlank() } ?: "Не указан",
                    creatorPhone = task.creatorMobilePhone?.takeIf { it.isNotBlank() }
                        ?: task.creatorPhone?.takeIf { it.isNotBlank() },
                    executors = task.executors?.takeIf { it.isNotBlank() } ?: session.displayName,
                    createdText = DateAndTextFormatter.formatIntraDate(task.created),
                    changedText = DateAndTextFormatter.formatIntraDate(task.changed),
                    deadlineText = DateAndTextFormatter.formatDeadline(task.deadline),
                    isOverdue = (task.resolutionOverdue == true),
                    isCurrentlyRepaired = isCurrentlyRepaired,
                    hasHadRepairedStatus = hasHadRepaired,
                    canSetRepaired = canSetRepaired,
                    repairedStatusId = repairedTargetId,
                    repairedStatusName = repairedTargetName,
                    isCommentRequiredForRepaired = isCommentRequired,
                    repairedUnavailableReason = unavailableReason,
                    canAttachFiles = canAttachFiles,
                    canComment = canComment,
                    attachments = attachments,
                    history = historyEvents
                )
            )
        } catch (t: Throwable) {
            Result.failure(toIntraException(t))
        }
    }

    suspend fun saveTicketChanges(
        taskId: Int,
        newStatusId: Int?,
        pendingPhotos: List<PendingPhoto>,
        comment: String,
        onStageChange: (String) -> Unit
    ): Result<TicketSaveResult> = withContext(Dispatchers.IO) {
        try {
            requireAuthenticatedSession()

            var fileTokens: String? = null
            if (pendingPhotos.isNotEmpty()) {
                onStageChange("Загрузка фото (${pendingPhotos.size})...")
                val parts = mutableListOf<MultipartBody.Part>()
                val jpegMediaType = "image/jpeg".toMediaType()

                pendingPhotos.forEachIndexed { index, photo ->
                    val fieldKey = "file$index"
                    val requestBody = photo.file.asRequestBody(jpegMediaType)
                    parts += MultipartBody.Part.createFormData(
                        fieldKey,
                        "${photo.nameWithoutExt}.jpg",
                        requestBody
                    )
                    parts += MultipartBody.Part.createFormData(
                        "${fieldKey}Name",
                        photo.nameWithoutExt
                    )
                }

                val uploadResp = api.uploadTaskFiles(parts)
                if (!uploadResp.isSuccessful) {
                    throw IllegalStateException("Ошибка загрузки фото: ${parseErrorMessage(uploadResp)}")
                }
                val uploadBody = uploadResp.body()
                    ?: throw IllegalStateException("Сервер не вернул токены загруженных файлов")
                val tokens = extractFileTokens(uploadBody)
                if (tokens.isBlank()) {
                    throw IllegalStateException("Сервер вернул пустой список FileTokens")
                }
                fileTokens = tokens
            }

            val trimmedComment = comment.trim().takeIf { it.isNotEmpty() }
            if (newStatusId == null && fileTokens == null && trimmedComment == null) {
                throw IllegalArgumentException("Нет изменений для сохранения")
            }

            onStageChange("Сохранение заявки...")
            val updateDto = UpdateTaskRequestDto(
                statusId = newStatusId,
                fileTokens = fileTokens,
                comment = trimmedComment
            )

            val putResp = api.updateTask(taskId = taskId, body = updateDto)
            if (!putResp.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(putResp))
            }

            ImageCompressor.cleanupUploadFiles(pendingPhotos)

            val statusChangedToRepaired = newStatusId != null &&
                isRepairedStatus(
                    statusId = newStatusId,
                    statusName = statusesById[newStatusId]?.name,
                    repairedIds = findRepairedStatusIds(statusesById.values)
                )
            if (statusChangedToRepaired) {
                locallyRepairedTaskIds.add(taskId)
                repairedHistoryCache.keys
                    .filter { it.startsWith("$taskId|") }
                    .forEach { repairedHistoryCache[it] = true }
            }

            Result.success(
                TicketSaveResult(
                    movedToRepairedTab = statusChangedToRepaired,
                    attachedCount = pendingPhotos.size,
                    statusChanged = newStatusId != null
                )
            )
        } catch (t: Throwable) {
            Result.failure(toIntraException(t))
        }
    }

    suspend fun downloadAttachmentForViewing(attachment: TaskAttachment): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                requireAuthenticatedSession()
                val resp = api.downloadTaskFile(attachment.id)
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Не удалось скачать файл: ${parseErrorMessage(resp)}")
                }
                val body = resp.body() ?: throw IllegalStateException("Пустой файл")
                val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
                val safeName = attachment.name.replace(Regex("[^A-Za-zА-Яа-я0-9._-]"), "_")
                    .ifBlank { "file_${attachment.id}" }
                val outFile = File(dir, "${attachment.id}_$safeName")
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        outFile
                    )
                )
            } catch (t: Throwable) {
                Result.failure(toIntraException(t))
            }
        }

    private fun requireAuthenticatedSession(): UserSession {
        return credentialsProvider.currentSession()
            ?: throw IllegalStateException("Требуется авторизация")
    }

    private suspend fun refreshStatusCatalog(): List<TaskStatusDto> {
        val resp = api.getStatuses()
        if (!resp.isSuccessful) {
            if (statusesById.isNotEmpty()) return statusesById.values.toList()
            throw IllegalStateException(parseErrorMessage(resp))
        }
        val list = resp.body().orEmpty()
        list.forEach { statusesById[it.id] = it }
        return list
    }

    private suspend fun checkTaskHadRepairedInLifetime(
        taskId: Int,
        repairedIds: Set<Int>
    ): Boolean? {
        return runCatching {
            var page = 1
            var pageCount = 1
            while (page <= pageCount && page <= 5) {
                val resp = api.getTaskLifetime(
                    taskId = taskId,
                    include = if (repairedIds.isEmpty()) "status" else null,
                    pageSize = 200,
                    page = page
                )
                if (!resp.isSuccessful) return null
                val body = resp.body() ?: return false
                body.statuses.forEach { statusesById[it.id] = it }
                val updatedRepairedIds = repairedIds + findRepairedStatusIds(body.statuses)
                val hit = body.taskLifetimes.any { ev ->
                    val stName = ev.statusId?.let { statusesById[it]?.name }
                    isRepairedStatus(ev.statusId, stName, updatedRepairedIds)
                }
                if (hit) return true
                pageCount = body.paginator?.pageCount?.coerceAtLeast(1) ?: 1
                if (body.taskLifetimes.isEmpty()) break
                page++
            }
            false
        }.getOrNull()
    }

    private fun toIntraException(t: Throwable): IntraNetworkException {
        if (t is VpnBlockedConnectionException) {
            return IntraNetworkException(
                message = t.message ?: "Сервер недоступен через активный VPN",
                isVpnBlocked = true,
                cause = t
            )
        }
        val msg = when (t) {
            is UnknownHostException ->
                "Не удалось подключиться к серверу intra.lantaservice.com. Проверьте интернет-соединение"
            is SocketTimeoutException ->
                "Превышено время ожидания ответа от сервера intra.lantaservice.com"
            is ConnectException ->
                "Сервер intra.lantaservice.com временно недоступен"
            is SSLException ->
                "Ошибка защищенного соединения (TLS/SSL) с сервером"
            else -> t.message?.takeIf { it.isNotBlank() } ?: "Ошибка сети"
        }
        return IntraNetworkException(message = msg, isVpnBlocked = false, cause = t)
    }

    private fun parseErrorMessage(response: Response<*>): String {
        val code = response.code()
        val errorText = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
        if (errorText.isNotBlank()) {
            val dto = runCatching { json.decodeFromString(IntraErrorDto.serializer(), errorText) }.getOrNull()
            val msg = listOfNotNull(dto?.message, dto?.messageDetail)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
            if (!msg.isNullOrBlank()) return msg
        }
        return when (code) {
            401 -> "Сессия истекла. Выполните вход повторно"
            403 -> "Недостаточно прав для выполнения операции"
            404 -> "Объект не найден на сервере"
            409 -> "Заявка была изменена другим пользователем"
            else -> "Ошибка сервера (HTTP $code)"
        }
    }

    companion object {
        const val REPAIRED_STATUS_NAME = "Починил"
        const val REPAIRED_STATUS_KEYWORD = "починил"

        fun extractEmailLocalPart(input: String): String? {
            val atIdx = input.indexOf('@')
            if (atIdx <= 0) return null
            return input.substring(0, atIdx).trim().takeIf { it.isNotEmpty() }
        }

        fun findRepairedStatusIds(statuses: Iterable<TaskStatusDto>): Set<Int> {
            val all = statuses.toList()
            val exact = all.filter { it.name.trim().equals(REPAIRED_STATUS_NAME, ignoreCase = true) }
            if (exact.isNotEmpty()) return exact.map { it.id }.toSet()
            return all.filter { it.name.contains(REPAIRED_STATUS_KEYWORD, ignoreCase = true) }
                .map { it.id }
                .toSet()
        }

        fun findBestRepairedTarget(toStatuses: List<TaskStatusSimpleViewDto>): TaskStatusSimpleViewDto? {
            return toStatuses.firstOrNull {
                it.name.trim().equals(REPAIRED_STATUS_NAME, ignoreCase = true)
            } ?: toStatuses.firstOrNull {
                it.name.contains(REPAIRED_STATUS_KEYWORD, ignoreCase = true)
            }
        }

        fun isRepairedStatus(
            statusId: Int?,
            statusName: String?,
            repairedIds: Set<Int>
        ): Boolean {
            if (statusId != null && statusId in repairedIds) return true
            val normalized = statusName?.trim().orEmpty()
            return normalized.equals(REPAIRED_STATUS_NAME, ignoreCase = true) ||
                normalized.contains(REPAIRED_STATUS_KEYWORD, ignoreCase = true)
        }

        fun parseTaskForm(raw: JsonElement, json: Json): TaskFormDto {
            return if (raw is JsonObject && raw.containsKey("Task")) {
                json.decodeFromJsonElement(TaskFormDto.serializer(), raw)
            } else {
                val singleTask = json.decodeFromJsonElement(TaskSummaryDto.serializer(), raw)
                TaskFormDto(task = singleTask)
            }
        }

        private fun lifetimeCacheKey(taskId: Int, changed: String?): String {
            return "$taskId|${changed.orEmpty()}"
        }
    }
}
