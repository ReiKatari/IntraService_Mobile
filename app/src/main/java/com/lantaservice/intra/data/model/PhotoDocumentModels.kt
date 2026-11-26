package com.lantaservice.intra.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.Locale

@Immutable
@Serializable
enum class PhotoDocumentType(
    val shortTitle: String,
    val fullTitle: String,
    val description: String,
    val filePrefix: String,
    val isRequired: Boolean,
    val captureButtonLabel: String
) {
    AVR(
        shortTitle = "АВР",
        fullTitle = "Акт выполненных работ",
        description = "Обязательно. Счётчик копий на бланке определяется автоматически",
        filePrefix = "AVR",
        isRequired = true,
        captureButtonLabel = "Снять АВР"
    ),
    TEST_LIST(
        shortTitle = "Тест-лист",
        fullTitle = "Тест-лист печати",
        description = "Обязательно. Подтверждение качества печати после работ",
        filePrefix = "TestList",
        isRequired = true,
        captureButtonLabel = "Снять ТЛ"
    ),
    CONFIG_LIST(
        shortTitle = "Лист конфигурации",
        fullTitle = "Лист конфигурации",
        description = "При необходимости. Сетевые и системные параметры устройства",
        filePrefix = "ConfigList",
        isRequired = false,
        captureButtonLabel = "Снять ЛК"
    )
}

/**
 * Pure JVM helper enforcing the 5-minute window for changing a photo's document type
 * before permanently locking it, as well as filename categorization rules.
 */
object PhotoDocumentTypeLock {

    const val LOCK_DURATION_MS: Long = 5L * 60L * 1000L

    fun canChangeType(createdAtEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (createdAtEpochMs <= 0L) return true
        val elapsed = (nowEpochMs - createdAtEpochMs).coerceAtLeast(0L)
        return elapsed < LOCK_DURATION_MS
    }

    fun remainingSeconds(createdAtEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Long {
        if (createdAtEpochMs <= 0L) return LOCK_DURATION_MS / 1000L
        val remainingMs = (LOCK_DURATION_MS - (nowEpochMs - createdAtEpochMs)).coerceAtLeast(0L)
        return (remainingMs + 999L) / 1000L
    }

    fun formatRemainingMmSs(createdAtEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): String {
        val totalSec = remainingSeconds(createdAtEpochMs, nowEpochMs)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    fun detectTypeFromFileName(fileName: String): PhotoDocumentType? {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("avr_") || lower.contains("_avr_") ||
                lower.contains("авр") || lower.contains("акт") -> PhotoDocumentType.AVR
            lower.startsWith("testlist_") || lower.startsWith("test_list_") ||
                lower.contains("testlist") || lower.contains("тест") -> PhotoDocumentType.TEST_LIST
            lower.startsWith("configlist_") || lower.startsWith("config_list_") ||
                lower.contains("configlist") || lower.contains("конфиг") -> PhotoDocumentType.CONFIG_LIST
            else -> null
        }
    }

    fun renamePrefix(nameWithoutExt: String, newType: PhotoDocumentType): String {
        val stripped = nameWithoutExt
            .removePrefix("AVR_")
            .removePrefix("TestList_")
            .removePrefix("ConfigList_")
            .removePrefix("photo_")
            .ifBlank { "doc" }
        return "${newType.filePrefix}_$stripped"
    }
}

@Immutable
data class SaveReadiness(
    val hasAvr: Boolean,
    val hasTestList: Boolean,
    val hasValidCounter: Boolean,
    val hasChanges: Boolean
) {
    val isReadyToSave: Boolean
        get() = hasChanges && hasAvr && hasTestList && hasValidCounter

    fun missingItems(): List<String> = buildList {
        if (!hasAvr) add("АВР")
        if (!hasTestList) add("Тест-лист")
        if (!hasValidCounter) add("Счётчик копий")
    }
}

/**
 * Pure JVM validator enforcing mandatory AVR + Test-List photos and valid print counter
 * before the Save action becomes active.
 */
object TaskSaveValidator {

    fun normalizeCounterDigits(raw: String): String {
        return raw.filter { it.isDigit() }.take(9)
    }

    fun isValidCounter(raw: String): Boolean {
        val clean = raw.trim()
        return clean.isNotEmpty() && clean.length <= 9 && clean.all { it.isDigit() }
    }

    fun evaluate(
        pendingPhotos: List<PendingPhoto>,
        existingAttachments: List<TaskAttachment>,
        counterValue: String,
        hasChanges: Boolean
    ): SaveReadiness {
        val hasPendingAvr = pendingPhotos.any { it.documentType == PhotoDocumentType.AVR }
        val hasServerAvr = existingAttachments.any {
            PhotoDocumentTypeLock.detectTypeFromFileName(it.name) == PhotoDocumentType.AVR
        }
        val hasPendingTest = pendingPhotos.any { it.documentType == PhotoDocumentType.TEST_LIST }
        val hasServerTest = existingAttachments.any {
            PhotoDocumentTypeLock.detectTypeFromFileName(it.name) == PhotoDocumentType.TEST_LIST
        }

        return SaveReadiness(
            hasAvr = hasPendingAvr || hasServerAvr,
            hasTestList = hasPendingTest || hasServerTest,
            hasValidCounter = isValidCounter(counterValue),
            hasChanges = hasChanges
        )
    }
}
