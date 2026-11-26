package com.lantaservice.intra.data.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lantaservice.intra.MainActivity
import com.lantaservice.intra.R
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.util.DateAndTextFormatter
import java.time.Duration
import java.time.LocalDateTime

/**
 * Pure JVM detector identifying newly assigned tickets and approaching deadlines (< 60 min).
 */
object DeadlineUrgencyDetector {

    fun findNewTickets(
        previousSeenIds: Set<Int>,
        currentTickets: List<TicketItem>
    ): List<TicketItem> {
        if (previousSeenIds.isEmpty()) return emptyList()
        return currentTickets.filter { it.id !in previousSeenIds && !it.hasHadRepairedStatus }
    }

    fun findUrgentDeadlines(
        tickets: List<TicketItem>,
        now: LocalDateTime,
        alreadyAlertedKeys: Set<String>,
        thresholdMinutes: Long = 60L
    ): List<Pair<TicketItem, Long>> {
        val urgent = mutableListOf<Pair<TicketItem, Long>>()
        for (ticket in tickets) {
            if (ticket.hasHadRepairedStatus) continue
            val rawDeadline = ticket.deadlineText.trim()
            if (rawDeadline.isEmpty() || rawDeadline.equals("Без срока", ignoreCase = true)) continue
            val deadlineDt = DateAndTextFormatter.parseDateTime(rawDeadline) ?: continue
            val minutesLeft = Duration.between(now, deadlineDt).toMinutes()
            if (minutesLeft in 1L..thresholdMinutes) {
                val alertKey = "${ticket.id}|$rawDeadline"
                if (alertKey !in alreadyAlertedKeys) {
                    urgent += ticket to minutesLeft
                }
            }
        }
        return urgent
    }
}

object TicketNotificationHelper {

    const val EXTRA_OPEN_TASK_ID = "intra_open_task_id"
    private const val CHANNEL_ID = "intra_ticket_alerts"
    private const val PREFS_NAME = "intra_notif_state"
    private const val KEY_SEEDED_PREFIX = "seeded_"
    private const val KEY_SEEN_IDS_PREFIX = "seen_ids_"
    private const val KEY_ALERTED_DEADLINES_PREFIX = "alerted_deadlines_"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Заявки и сроки IntraService",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Уведомления о новых назначенных заявках и сроках < 1 часа"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun processTicketsAndNotify(
        context: Context,
        userId: Int,
        tickets: List<TicketItem>
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seededKey = "$KEY_SEEDED_PREFIX$userId"
        val seenKey = "$KEY_SEEN_IDS_PREFIX$userId"
        val alertedKey = "$KEY_ALERTED_DEADLINES_PREFIX$userId"

        val currentIdSet = tickets.map { it.id.toString() }.toSet()
        val wasSeeded = prefs.getBoolean(seededKey, false)
        val prevSeenIds = prefs.getStringSet(seenKey, emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        val alertedDeadlines = prefs.getStringSet(alertedKey, emptySet()).orEmpty().toMutableSet()

        if (!wasSeeded) {
            prefs.edit()
                .putBoolean(seededKey, true)
                .putStringSet(seenKey, currentIdSet)
                .apply()
            // Still alert if an existing ticket deadline is < 1h right now
            val urgentInitial = DeadlineUrgencyDetector.findUrgentDeadlines(
                tickets = tickets,
                now = LocalDateTime.now(),
                alreadyAlertedKeys = alertedDeadlines
            )
            if (urgentInitial.isNotEmpty()) {
                ensureChannel(context)
                urgentInitial.forEach { (ticket, mins) ->
                    notifyUrgentDeadline(context, ticket, mins)
                    alertedDeadlines += "${ticket.id}|${ticket.deadlineText.trim()}"
                }
                prefs.edit().putStringSet(alertedKey, alertedDeadlines).apply()
            }
            return
        }

        val newTickets = DeadlineUrgencyDetector.findNewTickets(prevSeenIds, tickets)
        val urgentDeadlines = DeadlineUrgencyDetector.findUrgentDeadlines(
            tickets = tickets,
            now = LocalDateTime.now(),
            alreadyAlertedKeys = alertedDeadlines
        )

        if (newTickets.isNotEmpty() || urgentDeadlines.isNotEmpty()) {
            ensureChannel(context)
            newTickets.forEach { ticket ->
                notifyNewTicket(context, ticket)
            }
            urgentDeadlines.forEach { (ticket, mins) ->
                notifyUrgentDeadline(context, ticket, mins)
                alertedDeadlines += "${ticket.id}|${ticket.deadlineText.trim()}"
            }
        }

        val mergedSeen = (prevSeenIds.map { it.toString() } + currentIdSet).toSet()
        prefs.edit()
            .putStringSet(seenKey, mergedSeen)
            .putStringSet(alertedKey, alertedDeadlines.takeLastCompat(200))
            .apply()
    }

    private fun Set<String>.takeLastCompat(limit: Int): Set<String> {
        if (size <= limit) return this
        return toList().takeLast(limit).toSet()
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun buildTicketPendingIntent(context: Context, taskId: Int, requestOffset: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TASK_ID, taskId)
        }
        return PendingIntent.getActivity(
            context,
            taskId * 10 + requestOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notifyNewTicket(context: Context, ticket: TicketItem) {
        if (!canPostNotifications(context)) return
        val content = if (ticket.deadlineText.isNotBlank() && ticket.deadlineText != "Без срока") {
            "${ticket.name} - Срок: ${ticket.deadlineText}"
        } else {
            ticket.name
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo_mark)
            .setContentTitle("Новая заявка #${ticket.id}")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildTicketPendingIntent(context, ticket.id, 1))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(10_000 + ticket.id, notif)
        }
    }

    fun notifyUrgentDeadline(context: Context, ticket: TicketItem, minutesLeft: Long) {
        if (!canPostNotifications(context)) return
        val content = "До дедлайна осталось $minutesLeft мин (${ticket.deadlineText}): ${ticket.name}"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo_mark)
            .setContentTitle("Срочный срок по заявке #${ticket.id}")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildTicketPendingIntent(context, ticket.id, 2))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(20_000 + ticket.id, notif)
        }
    }
}
