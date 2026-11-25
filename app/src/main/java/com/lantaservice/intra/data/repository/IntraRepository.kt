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
import com.lantaservice.intra.data.model.extractFileTokensFromRaw
import com.lantaservice.intra.data.network.IntraApiService
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.network.VpnBlockedConnectionException
import com.lantaservice.intra.data.storage.OutboxSyncNotice
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.data.storage.PendingOutboxLogic
import com.lantaservice.intra.data.storage.PendingOutboxStorage
import com.lantaservice.intra.data.storage.SavedCredentials
import com.lantaservice.intra.data.storage.SecureSessionStorage
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.ImageCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

class IntraHttpException(
    val httpCode: Int,
    message: String
) : IllegalStateException(message)

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
    private val sessionStorage: SecureSessionStorage,
    private val outboxStorage: PendingOutboxStorage
) {

    // Always require explicit login screen when app process starts cleanly
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val unauthorizedEvents: Flow<Unit> = credentialsProvider.unauthorizedEvents

    private val _outboxEntries = MutableStateFlow<List<PendingOutboxEntry>>(emptyList())
    val outboxEntries: StateFlow<List<PendingOutboxEntry>> = _outboxEntries.asStateFlow()

    private val _isSyncingOutbox = MutableStateFlow(false)
    val isSyncingOutbox: StateFlow<Boolean> = _isSyncingOutbox.asStateFlow()

    private val _outboxSyncedEvents = MutableSharedFlow<OutboxSyncNotice>(extraBufferCapacity = 16)
    val outboxSyncedEvents: SharedFlow<OutboxSyncNotice> = _outboxSyncedEvents.asSharedFlow()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxSyncMutex = Mutex()
    private val statusesById = ConcurrentHashMap<Int, TaskStatusDto>()
    private val prioritiesById = ConcurrentHashMap<Int, TaskPriorityDto>()
    private val servicesById = ConcurrentHashMap<Int, String>()
    @Volatile
    private var statusesLastLoadedAtMs: Long = 0L
    @Volatile
    private var activePrefetchJob: Pair<Int, Deferred<Result<TicketListSnapshot>>>? = null
    private val repairedHistoryCache = ConcurrentHashMap<String, Boolean>()
    private val locallyRepairedTaskIds = ConcurrentHashMap.newKeySet<Int>()
    private val ticketItemCache = ConcurrentHashMap<Int, TicketItem>()
    private val detailCache = ConcurrentHashMap<Int, TicketDetail>()

    fun serverDisplayHost(): String = SessionCredentialsProvider.FIXED_BASE_URL
        .removePrefix("https://")
        .removePrefix("http://")

    fun loadPrefilledCredentials(): SavedCredentials? = sessionStorage.load()

    fun saveRememberedCredentials(
        login: String,
        password: String,
        rememberMe: Boolean
    ) {
        sessionStorage.save(
            SavedCredentials(
                baseUrl = SessionCredentialsProvider.FIXED_BASE_URL,
                login = login,
                password = password,
                rememberMe = rememberMe
            )
        )
    }

    private fun resetInMemoryCaches() {
        activePrefetchJob?.second?.cancel()
        activePrefetchJob = null
        statusesById.clear()
        prioritiesById.clear()
        servicesById.clear()
        statusesLastLoadedAtMs = 0L
        repairedHistoryCache.clear()
        locallyRepairedTaskIds.clear()
        ticketItemCache.clear()
        detailCache.clear()
        _outboxEntries.value = emptyList()
    }

    fun clearInMemorySession() {
        credentialsProvider.clearSession()
        resetInMemoryCaches()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * Clears active in-memory session immediately before Activity finishes and closes app,
     * while preserving encrypted login fields and durable offline outbox files on disk.
     */
    fun logoutImmediate() {
        credentialsProvider.clearSession()
        resetInMemoryCaches()
        repoScope.launch {
            ImageCompressor.cleanupCacheDirs(context)
        }
    }

    fun handleUnauthorized() {
        credentialsProvider.clearSession()
        resetInMemoryCaches()
        _authState.value = AuthState.Unauthenticated
    }

    fun getCachedTicketsForCurrentUser(): List<TicketItem> {
        val session = credentialsProvider.currentSession() ?: return emptyList()
        if (ticketItemCache.isNotEmpty()) {
            return ticketItemCache.values.sortedByDescending { it.id }
        }
        val file = File(context.filesDir, "cache_tickets_${session.userId}.json")
        if (!file.exists()) return emptyList()
        val loaded = runCatching {
            val raw = file.readText(Charsets.UTF_8)
            json.decodeFromString(ListSerializer(TicketItem.serializer()), raw)
        }.getOrNull().orEmpty()
        loaded.forEach { ticketItemCache[it.id] = it }
        return loaded
    }

    fun getCachedDetailPreview(taskId: Int): TicketDetail? {
        detailCache[taskId]?.let { return it }
        val item = ticketItemCache[taskId] ?: return null
        val statuses = statusesById.values.toList()
        val repairedStatus = statuses.firstOrNull {
            it.name.trim().equals(REPAIRED_STATUS_NAME, ignoreCase = true)
        } ?: statuses.firstOrNull {
            it.name.contains(REPAIRED_STATUS_KEYWORD, ignoreCase = true)
        }
        val isCurrRepaired = isRepairedStatus(item.statusId, item.statusName, findRepairedStatusIds(statuses))
        return TicketDetail(
            id = item.id,
            name = item.name,
            description = item.descriptionPreview.ifBlank { "Загрузка подробного описания..." },
            statusId = item.statusId,
            statusName = item.statusName,
            priorityName = item.priorityName,
            serviceName = item.serviceName,
            creatorName = item.creatorName.ifBlank { "Не указан" },
            creatorPhone = null,
            executors = credentialsProvider.currentSession()?.displayName.orEmpty().ifBlank { "Текущий исполнитель" },
            createdText = item.createdText,
            changedText = item.changedText,
            deadlineText = item.deadlineText,
            isOverdue = item.isOverdue,
            isCurrentlyRepaired = isCurrRepaired,
            hasHadRepairedStatus = item.hasHadRepairedStatus,
            canSetRepaired = !isCurrRepaired,
            repairedStatusId = repairedStatus?.id,
            repairedStatusName = repairedStatus?.name ?: REPAIRED_STATUS_NAME,
            isCommentRequiredForRepaired = repairedStatus?.isCommentRequired ?: false,
            repairedUnavailableReason = null,
            canAttachFiles = true,
            canComment = true,
            attachments = emptyList(),
            history = emptyList()
        )
    }

    fun getOutboxEntry(taskId: Int): PendingOutboxEntry? {
        val userId = credentialsProvider.currentSession()?.userId ?: return null
        return _outboxEntries.value.firstOrNull { it.taskId == taskId && it.userId == userId }
    }

    fun isTemporaryPhotoFile(file: File): Boolean = ImageCompressor.isInCacheUploads(context, file)

    fun cleanupTemporaryPhotos(photos: List<PendingPhoto>) =
        ImageCompressor.cleanupTemporaryUploadFiles(context, photos)

    fun cancelOutboxEntry(taskId: Int) {
        val userId = credentialsProvider.currentSession()?.userId ?: return
        val updated = PendingOutboxLogic.remove(outboxStorage.loadAll(), taskId, userId)
        outboxStorage.saveAll(updated)
        outboxStorage.deleteTaskPhotos(taskId)
        refreshOutboxFlowForUser(userId)
    }

    fun triggerOutboxSyncAsync() {
        val session = credentialsProvider.currentSession() ?: return
        if (_outboxEntries.value.isEmpty()) {
            refreshOutboxFlowForUser(session.userId)
            if (_outboxEntries.value.isEmpty()) return
        }
        repoScope.launch {
            syncPendingOutboxNow()
        }
    }

    private fun refreshOutboxFlowForUser(userId: Int) {
        _outboxEntries.value = PendingOutboxLogic.filterForUser(outboxStorage.loadAll(), userId)
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
            if (rememberMe) {
                saveRememberedCredentials(cleanInput, password, true)
            } else {
                saveRememberedCredentials("", "", false)
            }

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

            // Stage 2: If email entered (e.g. engineer@lantaservice.com), try Basic Auth with local-part
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
        warmCachesFromDisk(session.userId)
        refreshOutboxFlowForUser(session.userId)
        _authState.value = AuthState.Authenticated(session)
        startPrefetchOpenTickets(session.userId)
        if (_outboxEntries.value.isNotEmpty()) {
            repoScope.launch { syncPendingOutboxNow() }
        }
        return Result.success(session)
    }

    private fun warmCachesFromDisk(userId: Int) {
        if (statusesById.isEmpty()) {
            loadDiskStatusCatalog().forEach { statusesById[it.id] = it }
        }
        if (repairedHistoryCache.isEmpty()) {
            loadRepairedHistoryDiskCache(userId).forEach { (k, v) -> repairedHistoryCache[k] = v }
        }
        if (ticketItemCache.isEmpty()) {
            loadTicketListCacheFromDisk(userId).forEach { ticketItemCache[it.id] = it }
        }
    }

    private fun startPrefetchOpenTickets(userId: Int) {
        val current = activePrefetchJob
        if (current != null && current.first == userId && current.second.isActive) return
        val deferred = repoScope.async {
            executeOpenTicketsFetch {}
        }
        activePrefetchJob = userId to deferred
    }

    suspend fun loadOpenTicketsProgressive(
        onProgress: suspend (TicketListSnapshot) -> Unit
    ): Result<TicketListSnapshot> = withContext(Dispatchers.IO) {
        val session = try {
            requireAuthenticatedSession()
        } catch (t: Throwable) {
            return@withContext Result.failure(toIntraException(t))
        }
        val inFlight = activePrefetchJob
        if (inFlight != null && inFlight.first == session.userId) {
            activePrefetchJob = null
            val prefetched = inFlight.second.await()
            if (prefetched.isSuccess) {
                prefetched.getOrNull()?.let { onProgress(it) }
                return@withContext prefetched
            }
        }
        executeOpenTicketsFetch(onProgress)
    }

    private suspend fun executeOpenTicketsFetch(
        onProgress: suspend (TicketListSnapshot) -> Unit
    ): Result<TicketListSnapshot> = withContext(Dispatchers.IO) {
        try {
            val session = requireAuthenticatedSession()
            warmCachesFromDisk(session.userId)

            val statuses = if (statusesById.isNotEmpty()) {
                if ((System.currentTimeMillis() - statusesLastLoadedAtMs) >= STATUS_CACHE_TTL_MS) {
                    repoScope.launch { runCatching { refreshStatusCatalog(forceRefresh = true) } }
                }
                statusesById.values.toList()
            } else {
                refreshStatusCatalog()
            }
            val repairedIds = findRepairedStatusIds(statuses)
            val openStatusIdsCsv = statuses
                .filter { !it.isFinal }
                .takeIf { it.isNotEmpty() && it.size <= 80 }
                ?.joinToString(",") { it.id.toString() }

            val collectedTasks = mutableListOf<TaskSummaryDto>()
            val mergedStatuses = LinkedHashMap<Int, TaskStatusDto>().apply {
                statuses.forEach { put(it.id, it) }
            }
            val mergedPriorities = LinkedHashMap<Int, TaskPriorityDto>(prioritiesById)
            val mergedServices = LinkedHashMap<Int, String>(servicesById)

            val page1Resp = api.getTasks(
                executorIds = session.userId.toString(),
                statusIds = openStatusIdsCsv,
                pageSize = 200,
                page = 1
            )
            if (!page1Resp.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(page1Resp))
            }
            val page1Body = page1Resp.body()
            if (page1Body != null) {
                collectedTasks += page1Body.tasks
                page1Body.statuses.forEach { mergedStatuses[it.id] = it }
                page1Body.priorities.forEach { mergedPriorities[it.id] = it }
                page1Body.services.forEach { mergedServices[it.id] = it.name }

                val totalPages = (page1Body.paginator?.pageCount ?: 1).coerceIn(1, 10)
                if (totalPages > 1 && page1Body.tasks.isNotEmpty()) {
                    val page1Preview = buildTicketItems(
                        collectedTasks = collectedTasks,
                        mergedStatuses = mergedStatuses,
                        mergedPriorities = mergedPriorities,
                        mergedServices = mergedServices,
                        allRepairedIds = findRepairedStatusIds(mergedStatuses.values) + repairedIds
                    )
                    if (page1Preview.isNotEmpty()) {
                        onProgress(TicketListSnapshot(page1Preview, isResolvingHistory = true))
                    }

                    val extraResponses = coroutineScope {
                        (2..totalPages).map { p ->
                            async {
                                api.getTasks(
                                    executorIds = session.userId.toString(),
                                    statusIds = openStatusIdsCsv,
                                    pageSize = 200,
                                    page = p
                                )
                            }
                        }.awaitAll()
                    }
                    extraResponses.forEach { resp ->
                        if (resp.isSuccessful) {
                            val b = resp.body()
                            if (b != null) {
                                collectedTasks += b.tasks
                                b.statuses.forEach { mergedStatuses[it.id] = it }
                                b.priorities.forEach { mergedPriorities[it.id] = it }
                                b.services.forEach { mergedServices[it.id] = it.name }
                            }
                        }
                    }
                }
            }

            statusesById.putAll(mergedStatuses)
            prioritiesById.putAll(mergedPriorities)
            servicesById.putAll(mergedServices)
            val allRepairedIds = findRepairedStatusIds(mergedStatuses.values) + repairedIds

            val initialItems = buildTicketItems(
                collectedTasks = collectedTasks,
                mergedStatuses = mergedStatuses,
                mergedPriorities = mergedPriorities,
                mergedServices = mergedServices,
                allRepairedIds = allRepairedIds
            )
            saveTicketListCache(session.userId, initialItems)

            val needHistoryCheck = initialItems.filter { !it.isLifetimeChecked }
            if (needHistoryCheck.isEmpty()) {
                val finalSnap = TicketListSnapshot(initialItems, isResolvingHistory = false)
                onProgress(finalSnap)
                return@withContext Result.success(finalSnap)
            }

            onProgress(TicketListSnapshot(initialItems, isResolvingHistory = true))

            val semaphore = Semaphore(8)
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
            saveTicketListCache(session.userId, updatedItems)
            if (resolvedMap.isNotEmpty()) {
                saveRepairedHistoryDiskCache(session.userId)
            }
            val finalSnap = TicketListSnapshot(updatedItems, isResolvingHistory = false)
            onProgress(finalSnap)
            Result.success(finalSnap)
        } catch (t: Throwable) {
            Result.failure(toIntraException(t))
        }
    }

    private fun buildTicketItems(
        collectedTasks: List<TaskSummaryDto>,
        mergedStatuses: Map<Int, TaskStatusDto>,
        mergedPriorities: Map<Int, TaskPriorityDto>,
        mergedServices: Map<Int, String>,
        allRepairedIds: Set<Int>
    ): List<TicketItem> {
        val openTasks = collectedTasks.distinctBy { it.id }.filter { task ->
            val status = task.statusId?.let { mergedStatuses[it] }
            val isFinal = (status?.isFinal == true) || (task.statusIsFinal == true)
            val isClosed = !task.closed.isNullOrBlank()
            !isFinal && !isClosed
        }

        return openTasks.map { task ->
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
                            include = "status,priority,service",
                            lastCommentsOnTop = true,
                            pageSize = 100,
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
            form.priorities.forEach { prioritiesById[it.id] = it }
            form.services.forEach { servicesById[it.id] = it.name }

            val lifetimeBody = lifetimeResp?.body()
            lifetimeBody?.statuses?.forEach { statusesById[it.id] = it }
            lifetimeBody?.priorities?.forEach { prioritiesById[it.id] = it }
            lifetimeBody?.services?.forEach { servicesById[it.id] = it.name }

            val allStatuses = statusesById.values.toList()
            val allRepairedIds = findRepairedStatusIds(allStatuses)

            val currentStatusObj = task.statusId?.let { statusesById[it] }
                ?: form.statuses.firstOrNull { it.id == task.statusId }
            val currentStatusName = task.statusName?.takeIf { it.isNotBlank() }
                ?: currentStatusObj?.name
                ?: "Без статуса"

            val isCurrentlyRepaired = isRepairedStatus(task.statusId, currentStatusName, allRepairedIds)

            val lifetimes = lifetimeBody?.taskLifetimes.orEmpty()
            val hadRepairedInHistory = lifetimes.any { ev ->
                val evStatusId = extractLifetimeStatusId(ev)
                val evName = evStatusId?.let { statusesById[it]?.name }
                isRepairedStatus(evStatusId, evName, allRepairedIds)
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
                parseLifetimeEvent(
                    obj = ev,
                    statusesById = statusesById,
                    prioritiesById = prioritiesById,
                    servicesById = servicesById
                )
            }

            val priorityName = task.priorityName?.takeIf { it.isNotBlank() }
                ?: form.priorities.firstOrNull { it.id == task.priorityId }?.name
                ?: ""
            val serviceName = task.serviceName?.takeIf { it.isNotBlank() }
                ?: form.services.firstOrNull { it.id == task.serviceId }?.name
                ?: ""

            val detail = TicketDetail(
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
            detailCache[task.id] = detail
            Result.success(detail)
        } catch (t: Throwable) {
            if (isRecoverableOutboxFailure(t)) {
                val cached = getCachedDetailPreview(taskId)
                if (cached != null) {
                    return@withContext Result.success(cached)
                }
            }
            Result.failure(toIntraException(t))
        }
    }

    suspend fun saveTicketChanges(
        taskId: Int,
        taskName: String = "",
        newStatusId: Int?,
        newStatusName: String? = null,
        pendingPhotos: List<PendingPhoto>,
        comment: String,
        allowOfflineQueue: Boolean = true,
        forcedRecordedAtText: String? = null,
        onStageChange: (String) -> Unit
    ): Result<TicketSaveResult> = withContext(Dispatchers.IO) {
        val session = try {
            requireAuthenticatedSession()
        } catch (t: Throwable) {
            return@withContext Result.failure(toIntraException(t))
        }

        val nowEpochMs = System.currentTimeMillis()
        val recordedAtText = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now())
        val trimmedComment = comment.trim()
        if (newStatusId == null && pendingPhotos.isEmpty() && trimmedComment.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Нет изменений для сохранения"))
        }

        val effectivePhotos = if (!forcedRecordedAtText.isNullOrBlank()) {
            pendingPhotos.mapIndexed { idx, p ->
                p.copy(nameWithoutExt = buildDeferredPhotoName(taskId, forcedRecordedAtText, idx))
            }
        } else {
            pendingPhotos
        }
        val effectiveComment = if (!forcedRecordedAtText.isNullOrBlank()) {
            buildDeferredComment(trimmedComment, forcedRecordedAtText)
        } else {
            trimmedComment
        }

        try {
            uploadPhotosAndPutTask(
                taskId = taskId,
                newStatusId = newStatusId,
                photos = effectivePhotos,
                comment = effectiveComment,
                onStageChange = onStageChange
            )

            // Clean up temporary uploads & remove any superseded outbox item
            cancelOutboxEntry(taskId)
            ImageCompressor.cleanupTemporaryUploadFiles(context, pendingPhotos)
            detailCache.remove(taskId)

            val statusChangedToRepaired = markLocalRepairedIfNeeded(taskId, newStatusId)

            Result.success(
                TicketSaveResult(
                    movedToRepairedTab = statusChangedToRepaired,
                    attachedCount = pendingPhotos.size,
                    statusChanged = newStatusId != null,
                    deferred = false
                )
            )
        } catch (t: Throwable) {
            val httpCode = (t as? IntraHttpException)?.httpCode
            if (allowOfflineQueue && isRecoverableOutboxFailure(t, httpCode)) {
                val stagedPhotos = outboxStorage.stagePhotosForTask(taskId, pendingPhotos)
                ImageCompressor.cleanupTemporaryUploadFiles(context, pendingPhotos)

                val entry = PendingOutboxEntry(
                    taskId = taskId,
                    userId = session.userId,
                    taskName = taskName.ifBlank { ticketItemCache[taskId]?.name ?: "Заявка #$taskId" },
                    newStatusId = newStatusId,
                    newStatusName = newStatusName ?: newStatusId?.let { statusesById[it]?.name },
                    comment = trimmedComment,
                    photos = stagedPhotos,
                    recordedAtEpochMs = nowEpochMs,
                    recordedAtText = recordedAtText,
                    lastError = toIntraException(t).message,
                    attemptCount = (getOutboxEntry(taskId)?.attemptCount ?: 0) + 1
                )
                val allUpdated = PendingOutboxLogic.upsert(outboxStorage.loadAll(), entry)
                outboxStorage.saveAll(allUpdated)
                refreshOutboxFlowForUser(session.userId)

                return@withContext Result.success(
                    TicketSaveResult(
                        movedToRepairedTab = false,
                        attachedCount = stagedPhotos.size,
                        statusChanged = newStatusId != null,
                        deferred = true,
                        deferredRecordedAt = recordedAtText
                    )
                )
            }
            Result.failure(toIntraException(t))
        }
    }

    suspend fun syncPendingOutboxNow(): Int = withContext(Dispatchers.IO) {
        val session = credentialsProvider.currentSession() ?: return@withContext 0
        outboxSyncMutex.withLock {
            val userEntries = PendingOutboxLogic.filterForUser(outboxStorage.loadAll(), session.userId)
            if (userEntries.isEmpty()) {
                refreshOutboxFlowForUser(session.userId)
                return@withLock 0
            }

            _isSyncingOutbox.value = true
            var syncedCount = 0
            try {
                for (entry in userEntries) {
                    val livePhotos = entry.photos.mapIndexedNotNull { idx, p ->
                        val f = File(p.filePath)
                        if (!f.exists()) null
                        else PendingPhoto(
                            id = p.id,
                            file = f,
                            nameWithoutExt = buildDeferredPhotoName(entry.taskId, entry.recordedAtText, idx),
                            sizeBytes = f.length(),
                            isEnhanced = p.isEnhanced,
                            isBlurryWarning = p.isBlurryWarning,
                            sharpnessScore = p.sharpnessScore
                        )
                    }
                    val deferredComment = buildDeferredComment(entry.comment, entry.recordedAtText)

                    try {
                        uploadPhotosAndPutTask(
                            taskId = entry.taskId,
                            newStatusId = entry.newStatusId,
                            photos = livePhotos,
                            comment = deferredComment,
                            onStageChange = {}
                        )

                        val remaining = PendingOutboxLogic.remove(outboxStorage.loadAll(), entry.taskId, session.userId)
                        outboxStorage.saveAll(remaining)
                        outboxStorage.deleteTaskPhotos(entry.taskId)
                        refreshOutboxFlowForUser(session.userId)
                        detailCache.remove(entry.taskId)

                        val movedToRepaired = markLocalRepairedIfNeeded(entry.taskId, entry.newStatusId)
                        syncedCount++
                        _outboxSyncedEvents.tryEmit(
                            OutboxSyncNotice(
                                taskId = entry.taskId,
                                movedToRepairedTab = movedToRepaired,
                                recordedAtText = entry.recordedAtText,
                                attachedCount = livePhotos.size
                            )
                        )
                    } catch (t: Throwable) {
                        val httpCode = (t as? IntraHttpException)?.httpCode
                        val errMsg = toIntraException(t).message ?: "Ошибка синхронизации"
                        val updatedEntry = entry.copy(
                            lastError = errMsg,
                            attemptCount = entry.attemptCount + 1
                        )
                        val updatedAll = PendingOutboxLogic.upsert(outboxStorage.loadAll(), updatedEntry)
                        outboxStorage.saveAll(updatedAll)
                        refreshOutboxFlowForUser(session.userId)

                        if (isRecoverableOutboxFailure(t, httpCode)) {
                            // Intra is still unreachable, stop remaining queue items until recovery
                            break
                        }
                    }
                }
            } finally {
                _isSyncingOutbox.value = false
            }
            syncedCount
        }
    }

    private suspend fun uploadPhotosAndPutTask(
        taskId: Int,
        newStatusId: Int?,
        photos: List<PendingPhoto>,
        comment: String,
        onStageChange: (String) -> Unit
    ) {
        var fileTokens: String? = null
        if (photos.isNotEmpty()) {
            onStageChange("Загрузка фото (${photos.size})...")
            val parts = mutableListOf<MultipartBody.Part>()
            val jpegMediaType = "image/jpeg".toMediaType()

            photos.forEachIndexed { index, photo ->
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
                throw IntraHttpException(
                    uploadResp.code(),
                    "Ошибка загрузки фото: ${parseErrorMessage(uploadResp)}"
                )
            }
            val rawUpload = uploadResp.body()?.string().orEmpty()
            val tokens = extractFileTokensFromRaw(rawUpload, json)
            if (tokens.isBlank()) {
                throw IllegalStateException("Сервер не вернул токены загруженных файлов")
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
            throw IntraHttpException(putResp.code(), parseErrorMessage(putResp))
        }
        putResp.body()?.close()
    }

    private fun markLocalRepairedIfNeeded(taskId: Int, newStatusId: Int?): Boolean {
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
        return statusChangedToRepaired
    }

    private fun loadTicketListCacheFromDisk(userId: Int): List<TicketItem> {
        val file = File(context.filesDir, "cache_tickets_$userId.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TicketItem.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    private fun saveTicketListCache(userId: Int, items: List<TicketItem>) {
        items.forEach { ticketItemCache[it.id] = it }
        repoScope.launch {
            runCatching {
                val file = File(context.filesDir, "cache_tickets_$userId.json")
                val encoded = json.encodeToString(ListSerializer(TicketItem.serializer()), items)
                file.writeText(encoded, Charsets.UTF_8)
            }
        }
    }

    private fun loadRepairedHistoryDiskCache(userId: Int): Map<String, Boolean> {
        val file = File(context.filesDir, "cache_repaired_history_$userId.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), Boolean.serializer()),
                file.readText(Charsets.UTF_8)
            )
        }.getOrElse { emptyMap() }
    }

    private fun saveRepairedHistoryDiskCache(userId: Int) {
        val snapshot = repairedHistoryCache.entries
            .take(2000)
            .associate { it.key to it.value }
        repoScope.launch {
            runCatching {
                val file = File(context.filesDir, "cache_repaired_history_$userId.json")
                val encoded = json.encodeToString(
                    MapSerializer(String.serializer(), Boolean.serializer()),
                    snapshot
                )
                file.writeText(encoded, Charsets.UTF_8)
            }
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

    private suspend fun refreshStatusCatalog(forceRefresh: Boolean = false): List<TaskStatusDto> {
        val now = System.currentTimeMillis()
        if (statusesById.isEmpty()) {
            loadDiskStatusCatalog().forEach { statusesById[it.id] = it }
        }
        if (statusesById.isNotEmpty() && !forceRefresh && (now - statusesLastLoadedAtMs) < STATUS_CACHE_TTL_MS) {
            return statusesById.values.toList()
        }
        val resp = runCatching { api.getStatuses() }.getOrElse { err ->
            if (statusesById.isNotEmpty()) return statusesById.values.toList()
            throw err
        }
        if (!resp.isSuccessful) {
            if (statusesById.isNotEmpty()) return statusesById.values.toList()
            throw IllegalStateException(parseErrorMessage(resp))
        }
        val list = resp.body().orEmpty()
        list.forEach { statusesById[it.id] = it }
        statusesLastLoadedAtMs = now
        saveDiskStatusCatalog(list)
        return list
    }

    private fun loadDiskStatusCatalog(): List<TaskStatusDto> {
        val file = File(context.filesDir, "cache_statuses.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TaskStatusDto.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    private fun saveDiskStatusCatalog(list: List<TaskStatusDto>) {
        runCatching {
            val file = File(context.filesDir, "cache_statuses.json")
            file.writeText(json.encodeToString(ListSerializer(TaskStatusDto.serializer()), list), Charsets.UTF_8)
        }
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
                    val evStatusId = extractLifetimeStatusId(ev)
                    val stName = evStatusId?.let { statusesById[it]?.name }
                    isRepairedStatus(evStatusId, stName, updatedRepairedIds)
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

        private const val STATUS_CACHE_TTL_MS = 15L * 60L * 1000L

        fun isRecoverableOutboxFailure(t: Throwable, httpCode: Int? = (t as? IntraHttpException)?.httpCode): Boolean {
            if (httpCode != null) {
                return httpCode == 408 || httpCode == 429 || httpCode >= 500
            }
            return t is IOException ||
                t is IntraNetworkException ||
                t.cause is IOException
        }

        fun buildDeferredComment(userComment: String, recordedAtText: String): String {
            val clean = userComment.trim()
            val marker = "[Отложенная отправка: попытка от $recordedAtText]"
            if (clean.contains(marker)) return clean
            return if (clean.isEmpty()) marker else "$clean\n\n$marker"
        }

        fun buildDeferredPhotoName(taskId: Int, recordedAtText: String, index: Int): String {
            val compactStamp = recordedAtText
                .replace(".", "")
                .replace(":", "")
                .replace(" ", "_")
                .replace(Regex("[^A-Za-z0-9_-]"), "")
                .ifBlank { "queued" }
            return "task${taskId}_${compactStamp}_${index + 1}"
        }

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

        private val skippedLifetimeKeys = setOf(
            "id", "taskid", "editorid", "date", "ispublic", "issystem", "sendnotification",
            "mailid", "workflowid", "tasktypeid", "parentid", "fileids", "filenames",
            "addedfileids", "removedfileids", "addedfiles", "removedfiles", "files",
            "editor", "editorname", "statusid", "status", "statusname", "comments", "comment"
        )

        fun extractLifetimeStatusId(obj: JsonObject): Int? {
            return obj.intField("StatusId")
        }

        fun parseLifetimeEvent(
            obj: JsonObject,
            statusesById: Map<Int, TaskStatusDto> = emptyMap(),
            prioritiesById: Map<Int, TaskPriorityDto> = emptyMap(),
            servicesById: Map<Int, String> = emptyMap()
        ): TicketLifetimeEvent {
            val statusId = obj.intField("StatusId")
            val explicitStatus = obj.stringField("StatusName") ?: obj.stringField("Status")
            val statusName = explicitStatus?.takeIf { it.isNotBlank() }
                ?: statusId?.let { statusesById[it]?.name }
                ?: statusId?.let { "ID $it" }
                ?: ""

            val dateText = DateAndTextFormatter.formatIntraDate(obj.stringField("Date"))
            val editorName = obj.stringField("Editor")
                ?: obj.stringField("EditorName")
                ?: "Система"
            val comment = DateAndTextFormatter.cleanHtmlText(
                obj.stringField("Comments") ?: obj.stringField("Comment")
            )
            val isPublic = obj.boolField("IsPublic")

            val addedFiles = DateAndTextFormatter.parseLifetimeFileNames(obj.stringField("AddedFiles"))
            val removedFiles = DateAndTextFormatter.parseLifetimeFileNames(obj.stringField("RemovedFiles"))
            val allFiles = DateAndTextFormatter.parseLifetimeFileNames(obj.stringField("Files"))

            val changes = mutableListOf<String>()
            val executors = obj.stringField("Executors")
            if (!executors.isNullOrBlank()) {
                changes += "Исполнители: $executors"
            }
            val execGroup = obj.stringField("ExecutorsGroup") ?: obj.stringField("ExecutorGroup")
            if (!execGroup.isNullOrBlank()) {
                changes += "Группа исполнителей: $execGroup"
            }
            val creator = obj.stringField("Creator")
            if (!creator.isNullOrBlank()) {
                changes += "Заявитель: $creator"
            }
            val participants = obj.stringField("Participants") ?: obj.stringField("Observers")
            if (!participants.isNullOrBlank()) {
                changes += "Наблюдатели: $participants"
            }
            val priorityId = obj.intField("PriorityId")
            val priorityText = obj.stringField("Priority")
                ?: priorityId?.let { prioritiesById[it]?.name ?: "ID $it" }
            if (!priorityText.isNullOrBlank()) {
                changes += "Приоритет: $priorityText"
            }
            val serviceId = obj.intField("ServiceId")
            val serviceText = obj.stringField("Service")
                ?: serviceId?.let { servicesById[it] ?: "ID $it" }
            if (!serviceText.isNullOrBlank()) {
                changes += "Сервис: $serviceText"
            }
            val stage = obj.stringField("ServiceStage")
            if (!stage.isNullOrBlank()) {
                changes += "Этап сервиса: $stage"
            }
            val categories = obj.stringField("Categories")
            if (!categories.isNullOrBlank()) {
                changes += "Категории: $categories"
            }
            val assets = obj.stringField("Assets")
            if (!assets.isNullOrBlank()) {
                changes += "Активы: $assets"
            }
            val coordinators = obj.stringField("Coordinators")
            if (!coordinators.isNullOrBlank()) {
                changes += "Согласующие: $coordinators"
            }
            val title = obj.stringField("Name")
            if (!title.isNullOrBlank()) {
                changes += "Название: $title"
            }
            val desc = DateAndTextFormatter.cleanHtmlText(obj.stringField("Description"))
            if (desc.isNotBlank()) {
                changes += "Описание: $desc"
            }
            val deadlineRaw = obj.stringField("Deadline")
            if (!deadlineRaw.isNullOrBlank()) {
                changes += "Срок: ${DateAndTextFormatter.formatIntraDate(deadlineRaw)}"
            }
            val reactionRaw = obj.stringField("ReactionDate") ?: obj.stringField("ReactionDateFact")
            if (!reactionRaw.isNullOrBlank()) {
                changes += "Срок реакции: ${DateAndTextFormatter.formatIntraDate(reactionRaw)}"
            }
            val resolutionRaw = obj.stringField("ResolutionDateFact")
            if (!resolutionRaw.isNullOrBlank()) {
                changes += "Факт. выполнение: ${DateAndTextFormatter.formatIntraDate(resolutionRaw)}"
            }
            val completion = obj.stringField("CompletionStatus")
            if (!completion.isNullOrBlank()) {
                changes += "Итог выполнения: $completion"
            }
            val eval = obj.stringField("Evaluation") ?: obj.intField("EvaluationId")?.let { "ID $it" }
            if (!eval.isNullOrBlank()) {
                changes += "Оценка: $eval"
            }

            if (addedFiles.isEmpty() && removedFiles.isEmpty() && allFiles.isNotEmpty()) {
                changes += "Файлы в заявке: ${allFiles.joinToString(", ")}"
            }

            val handledKeys = setOf(
                "executors", "executorsgroup", "executorgroup", "creator", "participants", "observers",
                "priorityid", "priority", "serviceid", "service", "servicestage", "categories",
                "assets", "coordinators", "name", "description", "deadline", "reactiondate",
                "reactiondatefact", "resolutiondatefact", "completionstatus", "evaluation", "evaluationid"
            )
            obj.entries.forEach { (key, value) ->
                val lowerKey = key.lowercase()
                if (lowerKey in skippedLifetimeKeys || lowerKey in handledKeys) return@forEach
                val prim = value as? JsonPrimitive ?: return@forEach
                if (prim is JsonNull) return@forEach
                val text = DateAndTextFormatter.cleanHtmlText(prim.content)
                if (text.isNotBlank()) {
                    changes += "$key: $text"
                }
            }

            if (statusName.isBlank() && comment.isBlank() && addedFiles.isEmpty() && removedFiles.isEmpty() && changes.isEmpty()) {
                changes += "Обновление заявки"
            }

            return TicketLifetimeEvent(
                dateText = dateText,
                editorName = editorName,
                statusId = statusId,
                statusName = statusName,
                comment = comment,
                isPublic = isPublic,
                addedFiles = addedFiles,
                removedFiles = removedFiles,
                changes = changes
            )
        }

        private fun JsonObject.stringField(key: String): String? {
            val el = this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val prim = el as? JsonPrimitive ?: return null
            if (prim is JsonNull) return null
            return prim.content.trim().takeIf { it.isNotEmpty() }
        }

        private fun JsonObject.intField(key: String): Int? {
            val el = this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val prim = el as? JsonPrimitive ?: return null
            if (prim is JsonNull) return null
            return prim.intOrNull ?: prim.content.trim().toIntOrNull()
        }

        private fun JsonObject.boolField(key: String): Boolean? {
            val el = this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val prim = el as? JsonPrimitive ?: return null
            if (prim is JsonNull) return null
            return prim.booleanOrNull
        }

        private fun lifetimeCacheKey(taskId: Int, changed: String?): String {
            return "$taskId|${changed.orEmpty()}"
        }
    }
}
