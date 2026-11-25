package com.lantaservice.intra.util

import android.text.Html
import com.lantaservice.intra.data.model.TaskAttachment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateAndTextFormatter {

    private val ruLocale = Locale.forLanguageTag("ru")
    private val outputDateOnlyFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", ruLocale)
    private val outputDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", ruLocale)

    private val inputDateOnlyFormatters = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    )

    private val inputDateTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm"),
        DateTimeFormatter.ofPattern("d.M.yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("d.M.yyyy H:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")
    )

    private val aspNetDateRegex = Regex("^/Date\\((-?\\d+)([+-]\\d{4})?\\)/$")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    fun formatLocalDate(date: LocalDate): String = date.format(outputDateOnlyFormatter)

    fun formatLocalDateTime(dateTime: LocalDateTime): String = dateTime.format(outputDateTimeFormatter)

    fun formatDateOnly(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        parseDateOnly(text)?.let { return formatLocalDate(it) }
        parseDateTime(text)?.let { return formatLocalDate(it.toLocalDate()) }
        return text
    }

    fun formatIntraDate(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        parseDateOnly(text)?.let { return formatLocalDate(it) }
        parseDateTime(text)?.let { return formatLocalDateTime(it) }
        return text
    }

    fun formatDeadline(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Без срока"
        return formatIntraDate(text)
    }

    fun parseDateOnly(raw: String): LocalDate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(':') || trimmed.contains('T') || trimmed.startsWith("/Date(")) {
            return null
        }
        for (formatter in inputDateOnlyFormatters) {
            runCatching {
                return LocalDate.parse(trimmed, formatter)
            }
        }
        return null
    }

    fun parseDateTime(raw: String): LocalDateTime? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        aspNetDateRegex.matchEntire(trimmed)?.let { match ->
            val millis = match.groupValues[1].toLongOrNull() ?: return null
            return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }

        runCatching {
            val offsetDateTime = OffsetDateTime.parse(trimmed)
            return if (trimmed.endsWith("Z", ignoreCase = true)) {
                offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            } else {
                offsetDateTime.toLocalDateTime()
            }
        }

        runCatching {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }

        for (formatter in inputDateTimeFormatters) {
            runCatching {
                return LocalDateTime.parse(trimmed, formatter)
            }
        }
        return null
    }

    fun cleanHtmlText(raw: String?): String {
        val source = raw?.trim().orEmpty()
        if (source.isEmpty()) return ""
        val looksLikeHtml = source.contains('<') && source.contains('>')
        val decoded = if (looksLikeHtml) {
            Html.fromHtml(source, Html.FROM_HTML_MODE_COMPACT).toString()
        } else {
            source
        }
        return decoded
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun parseAttachments(
        fileIdsRaw: String?,
        fileNamesRaw: String?,
        legacyFilesRaw: String?,
        baseUrl: String
    ): List<TaskAttachment> {
        val ids = splitIds(fileIdsRaw)
        if (ids.isEmpty()) return emptyList()
        val namesSource = fileNamesRaw?.takeIf { it.isNotBlank() } ?: legacyFilesRaw
        val names = splitNames(namesSource, expectedCount = ids.size)
        val normalizedBase = baseUrl.trimEnd('/')

        return ids.mapIndexed { idx, id ->
            val name = names.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "Файл #$id"
            TaskAttachment(
                id = id,
                name = name,
                isImage = isImageFileName(name),
                url = "$normalizedBase/api/taskfile/$id"
            )
        }
    }

    fun splitIds(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val sep = if (text.contains("||")) "||" else ","
        return text.split(sep)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun splitNames(raw: String?, expectedCount: Int): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return when {
            text.contains("||") -> text.split("||").map { it.trim() }
            expectedCount > 1 -> {
                val commaParts = text.split(",").map { it.trim() }
                if (commaParts.size == expectedCount) commaParts else listOf(text)
            }
            else -> listOf(text)
        }
    }

    fun isImageFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()
        return ext in imageExtensions
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f КБ", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f МБ", mb)
    }

    fun initialsOf(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "ИС"
        if (parts.size == 1) return parts[0].take(2).uppercase(ruLocale)
        return "${parts[0].first()}${parts[1].first()}".uppercase(ruLocale)
    }
}
