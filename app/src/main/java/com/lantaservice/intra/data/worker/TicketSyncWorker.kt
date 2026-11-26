package com.lantaservice.intra.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lantaservice.intra.IntraApplication
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.util.DateAndTextFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job polling assigned tickets in background (every 15 min)
 * so engineers receive notifications about new assignments and < 1 hour deadlines
 * even when the app is closed or minimized.
 */
class TicketSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? IntraApplication ?: return@withContext Result.success()
        val repository = app.repository

        // Try active session first, otherwise do a lightweight authenticated check via remembered credentials
        val activeSession = app.credentialsProvider.currentSession()
        if (activeSession != null) {
            val ticketsResult = repository.loadOpenTicketsProgressive { }
            ticketsResult.onSuccess { snap ->
                TicketNotificationHelper.processTicketsAndNotify(
                    context = applicationContext,
                    userId = activeSession.userId,
                    tickets = snap.tickets
                )
            }
            return@withContext Result.success()
        }

        val saved = app.sessionStorage.load() ?: return@withContext Result.success()
        if (!saved.rememberMe || saved.login.isBlank() || saved.password.isEmpty()) {
            return@withContext Result.success()
        }

        val loginInput = saved.login.trim()
        val directHeader = SessionCredentialsProvider.buildBasicAuthHeader(loginInput, saved.password)
        val userResp = runCatching {
            app.apiService.getCurrentUserInfo(
                getCurrentUserInfo = true,
                explicitAuthHeader = directHeader
            )
        }.getOrNull()

        val resolvedUserId: Int
        if (userResp != null && userResp.isSuccessful && userResp.body() != null) {
            resolvedUserId = userResp.body()!!.id
        } else {
            val localPart = IntraRepository.extractEmailLocalPart(loginInput)
            if (localPart.isNullOrBlank()) {
                return@withContext Result.success()
            }
            val localHeader = SessionCredentialsProvider.buildBasicAuthHeader(localPart, saved.password)
            val localResp = runCatching {
                app.apiService.getCurrentUserInfo(
                    getCurrentUserInfo = true,
                    explicitAuthHeader = localHeader
                )
            }.getOrNull() ?: return@withContext Result.retry()
            val body = localResp.body() ?: return@withContext Result.success()
            if (!localResp.isSuccessful) return@withContext Result.success()
            resolvedUserId = body.id
        }

        // Temporarily authorize background call safely using a direct header or cookie-free request?
        // Wait: use api.getTasks only after verifying credential match, and restore credentialsProvider cleanly.
        val resp = runCatching {
            val tmpCreds = app.credentialsProvider
            val wasEmpty = tmpCreds.currentSession() == null
            if (wasEmpty) {
                val tempHeaderSession = com.lantaservice.intra.data.model.UserSession(
                    baseUrl = SessionCredentialsProvider.FIXED_BASE_URL,
                    login = loginInput,
                    password = saved.password,
                    authCookieHeader = null,
                    userId = resolvedUserId,
                    displayName = "",
                    email = loginInput,
                    phone = null,
                    position = null,
                    roleType = null
                )
                tmpCreds.setSession(tempHeaderSession)
                try {
                    app.apiService.getTasks(
                        executorIds = resolvedUserId.toString(),
                        pageSize = 100,
                        page = 1
                    )
                } finally {
                    // Restore unauthenticated state so reopening app still displays LoginScreen
                    if (repository.authState.value !is com.lantaservice.intra.data.repository.AuthState.Authenticated) {
                        tmpCreds.clearSession()
                    }
                }
            } else {
                app.apiService.getTasks(
                    executorIds = resolvedUserId.toString(),
                    pageSize = 100,
                    page = 1
                )
            }
        }.getOrNull() ?: return@withContext Result.retry()

        if (!resp.isSuccessful) return@withContext Result.success()
        val body = resp.body() ?: return@withContext Result.success()
        val statusesById = body.statuses.associateBy { it.id }
        val repairedIds = IntraRepository.findRepairedStatusIds(body.statuses)

        val items = body.tasks
            .filter { task ->
                val st = task.statusId?.let { statusesById[it] }
                val stName = task.statusName?.takeIf { it.isNotBlank() } ?: st?.name ?: ""
                val isFinal = (st?.isFinal == true) || (task.statusIsFinal == true)
                val isClosed = !task.closed.isNullOrBlank()
                !isFinal && !isClosed && IntraRepository.isAllowedListStatusName(stName)
            }
            .map { task ->
                val st = task.statusId?.let { statusesById[it] }
                val stName = task.statusName?.takeIf { it.isNotBlank() } ?: st?.name ?: "Без статуса"
                val isRepaired = IntraRepository.isRepairedStatus(task.statusId, stName, repairedIds)
                com.lantaservice.intra.data.model.TicketItem(
                    id = task.id,
                    name = task.name?.takeIf { it.isNotBlank() } ?: "Заявка #${task.id}",
                    descriptionPreview = DateAndTextFormatter.cleanHtmlText(task.description),
                    statusId = task.statusId,
                    statusName = stName,
                    priorityId = task.priorityId,
                    priorityName = task.priorityName.orEmpty(),
                    serviceName = task.serviceName.orEmpty(),
                    creatorName = task.creator.orEmpty(),
                    createdText = DateAndTextFormatter.formatIntraDate(task.created),
                    changedText = DateAndTextFormatter.formatIntraDate(task.changed),
                    changedRaw = task.changed.orEmpty(),
                    deadlineText = DateAndTextFormatter.formatDeadline(task.deadline),
                    isOverdue = task.resolutionOverdue == true,
                    attachmentCount = 0,
                    hasHadRepairedStatus = isRepaired,
                    isLifetimeChecked = isRepaired
                )
            }

        TicketNotificationHelper.processTicketsAndNotify(
            context = applicationContext,
            userId = resolvedUserId,
            tickets = items
        )
        Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "intra_ticket_monitor"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workReq = PeriodicWorkRequestBuilder<TicketSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workReq
                )
            }
        }
    }
}
