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
            runCatching {
                Html.fromHtml(source, Html.FROM_HTML_MODE_COMPACT).toString()
            }.getOrElse {
                source
                    .replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("(?i)</(p|div|li|tr)>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
            }
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

    fun parseLifetimeFileNames(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val rawTokens = if (text.contains("||")) {
            text.split("||")
        } else {
            text.split(",")
        }
        val result = LinkedHashSet<String>()
        rawTokens.forEach { token ->
            val cleanToken = token.trim()
            if (cleanToken.isEmpty()) return@forEach
            val pipeIdx = cleanToken.indexOf('|')
            val fileName = if (pipeIdx > 0 && cleanToken.substring(0, pipeIdx).trim().all { it.isDigit() }) {
                cleanToken.substring(pipeIdx + 1).trim()
            } else {
                cleanToken
            }
            if (fileName.isNotEmpty()) {
                result.add(fileName)
            }
        }
        return result.toList()
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

    private val ticketAddressLabelRegex = Regex(
        "(?iu)^(?:[-*•]\\s*)?(?:фактический\\s+|почтовый\\s+)?(?:адрес(?:\\s+(?:объекта|клиента|доставки|установки|точки|филиала|офиса))?|местоположение|местонахождение|address)\\s*[:=-]?\\s*(.*)$"
    )
    private val russianStreetCueRegex = Regex(
        "(?iu)(?:^|[,\\s(])(?:ул\\.|улица|пр-?т|просп(?:ект)?\\.?|пер\\.|переулок|ш\\.|шоссе|б-р|бульвар|наб\\.|мкр\\.?|микрорайон|пр-?д|проезд|пл\\.|площадь|пгт\\.?|город\\s+[А-ЯЁ]|г\\.\\s*[А-ЯЁ])"
    )
    private val houseOrIndexCueRegex = Regex("(?iu)(?:д\\.|дом|корп\\.|стр\\.|\\b\\d{6}\\b|\\d+[а-яa-z]?(?:/\\d+)?)")

    fun extractTicketAddress(
        title: String?,
        description: String?,
        explicitAddress: String? = null
    ): String {
        fun cleanCandidate(raw: String): String {
            return raw.trim()
                .replace(Regex("\\s+"), " ")
                .trim(',', ';', '.', ' ')
                .take(200)
        }

        fun isValidAddress(cand: String): Boolean {
            if (cand.length < 6) return false
            val low = cand.lowercase(ruLocale)
            if (low in setOf("не указан", "не указана", "не указано", "нет", "none", "null", "n/a", "-", "--")) {
                return false
            }
            if (low.startsWith("http://") || low.startsWith("https://")) return false
            return cand.any { it.isLetter() }
        }

        explicitAddress?.let {
            val cleaned = cleanCandidate(it)
            if (isValidAddress(cleaned)) return cleaned
        }

        val descLines = description.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in descLines.indices) {
            val m = ticketAddressLabelRegex.matchEntire(descLines[i]) ?: continue
            val inlineVal = cleanCandidate(m.groupValues[1])
            if (isValidAddress(inlineVal)) return inlineVal
            if (inlineVal.isEmpty() && i + 1 < descLines.size) {
                val nextVal = cleanCandidate(descLines[i + 1])
                if (isValidAddress(nextVal)) return nextVal
            }
        }

        for (line in descLines) {
            val cleaned = cleanCandidate(line)
            if (isValidAddress(cleaned) &&
                russianStreetCueRegex.containsMatchIn(cleaned) &&
                (houseOrIndexCueRegex.containsMatchIn(cleaned) || cleaned.contains(','))
            ) {
                return cleaned
            }
        }

        val titleClean = cleanCandidate(title.orEmpty())
        if (isValidAddress(titleClean) &&
            russianStreetCueRegex.containsMatchIn(titleClean) &&
            houseOrIndexCueRegex.containsMatchIn(titleClean)
        ) {
            return titleClean
        }

        return ""
    }
}
