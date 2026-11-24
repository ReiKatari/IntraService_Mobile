package com.lantaservice.intra.util

import android.text.Html
import com.lantaservice.intra.data.model.TaskAttachment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateAndTextFormatter {

    private val outputTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru"))
    private val outputFullFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
    private val outputDateOnlyFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("ru"))

    private val inputDateTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    fun formatIntraDate(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val parsed = parseDateTime(text) ?: return text
        val today = LocalDate.now()
        return when (parsed.toLocalDate()) {
            today -> "Сегодня, ${parsed.format(outputTimeFormatter)}"
            today.minusDays(1) -> "Вчера, ${parsed.format(outputTimeFormatter)}"
            else -> parsed.format(outputFullFormatter)
        }
    }

    fun formatDeadline(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Без срока"
        val parsed = parseDateTime(text)
        if (parsed != null) return parsed.format(outputFullFormatter)
        val dateOnly = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
        return dateOnly?.format(outputDateOnlyFormatter) ?: text
    }

    fun parseDateTime(raw: String): LocalDateTime? {
        runCatching {
            return OffsetDateTime.parse(raw).toLocalDateTime()
        }
        for (formatter in inputDateTimeFormatters) {
            runCatching {
                return LocalDateTime.parse(raw, formatter)
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
        if (parts.size == 1) return parts[0].take(2).uppercase(Locale.forLanguageTag("ru"))
        return "${parts[0].first()}${parts[1].first()}".uppercase(Locale.forLanguageTag("ru"))
    }
}
