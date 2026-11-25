package com.lantaservice.intra.data.storage

import android.content.Context
import androidx.compose.runtime.Immutable
import com.lantaservice.intra.data.model.PendingPhoto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Immutable
@Serializable
data class PendingOutboxPhoto(
    val id: String,
    val filePath: String,
    val nameWithoutExt: String,
    val sizeBytes: Long,
    val isEnhanced: Boolean = true,
    val isBlurryWarning: Boolean = false,
    val sharpnessScore: Double = 0.0
)

@Immutable
@Serializable
data class PendingOutboxEntry(
    val taskId: Int,
    val userId: Int,
    val taskName: String = "",
    val newStatusId: Int? = null,
    val newStatusName: String? = null,
    val comment: String = "",
    val photos: List<PendingOutboxPhoto> = emptyList(),
    val recordedAtEpochMs: Long,
    val recordedAtText: String,
    val lastError: String? = null,
    val attemptCount: Int = 1
)

@Immutable
data class OutboxSyncNotice(
    val taskId: Int,
    val movedToRepairedTab: Boolean,
    val recordedAtText: String,
    val attachedCount: Int
)

/**
 * Pure JVM-testable helper operations on deferred Outbox queue entries.
 */
object PendingOutboxLogic {

    fun upsert(
        entries: List<PendingOutboxEntry>,
        updated: PendingOutboxEntry
    ): List<PendingOutboxEntry> {
        return (entries.filterNot { it.taskId == updated.taskId && it.userId == updated.userId } + updated)
            .sortedBy { it.recordedAtEpochMs }
    }

    fun remove(
        entries: List<PendingOutboxEntry>,
        taskId: Int,
        userId: Int
    ): List<PendingOutboxEntry> {
        return entries.filterNot { it.taskId == taskId && it.userId == userId }
    }

    fun filterForUser(
        entries: List<PendingOutboxEntry>,
        userId: Int
    ): List<PendingOutboxEntry> {
        return entries
            .filter { it.userId == userId }
            .sortedBy { it.recordedAtEpochMs }
    }
}

/**
 * Durable on-disk queue in app internal storage (filesDir/pending_outbox)
 * keeping deferred ticket changes and staged photos safe across app restarts and cache clears.
 */
class PendingOutboxStorage(
    context: Context,
    private val json: Json
) {
    private val outboxRoot = File(context.filesDir, "pending_outbox").apply { mkdirs() }
    private val queueFile = File(outboxRoot, "queue.json")
    private val tempQueueFile = File(outboxRoot, "queue.json.tmp")
    private val lock = Any()

    fun loadAll(): List<PendingOutboxEntry> = synchronized(lock) {
        if (!queueFile.exists()) return emptyList()
        val raw = runCatching { queueFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PendingOutboxEntry.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    fun saveAll(entries: List<PendingOutboxEntry>) = synchronized(lock) {
        outboxRoot.mkdirs()
        val encoded = json.encodeToString(ListSerializer(PendingOutboxEntry.serializer()), entries)
        tempQueueFile.writeText(encoded, Charsets.UTF_8)
        if (!tempQueueFile.renameTo(queueFile)) {
            queueFile.writeText(encoded, Charsets.UTF_8)
            runCatching { tempQueueFile.delete() }
        }
    }

    fun stagePhotosForTask(taskId: Int, photos: List<PendingPhoto>): List<PendingOutboxPhoto> = synchronized(lock) {
        val taskDir = File(outboxRoot, "task_$taskId").apply { mkdirs() }
        val keptPaths = HashSet<String>()
        val staged = mutableListOf<PendingOutboxPhoto>()

        photos.forEachIndexed { idx, photo ->
            val src = photo.file
            if (!src.exists()) return@forEachIndexed
            val targetFile = if (isInsideDir(src, taskDir)) {
                src
            } else {
                val safeBase = photo.nameWithoutExt.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "photo_$idx" }
                val dest = File(taskDir, "${safeBase}_${photo.id.take(6)}.jpg")
                src.copyTo(dest, overwrite = true)
                dest
            }
            val canonical = runCatching { targetFile.canonicalPath }.getOrDefault(targetFile.absolutePath)
            keptPaths.add(canonical)
            staged += PendingOutboxPhoto(
                id = photo.id,
                filePath = targetFile.absolutePath,
                nameWithoutExt = photo.nameWithoutExt,
                sizeBytes = targetFile.length(),
                isEnhanced = photo.isEnhanced,
                isBlurryWarning = photo.isBlurryWarning,
                sharpnessScore = photo.sharpnessScore
            )
        }

        // Remove stale photos from previous attempts that the user deleted/replaced
        taskDir.listFiles()?.forEach { child ->
            val childCanon = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
            if (childCanon !in keptPaths) {
                runCatching { child.delete() }
            }
        }
        if (staged.isEmpty()) {
            runCatching { taskDir.deleteRecursively() }
        }
        staged
    }

    fun deleteTaskPhotos(taskId: Int) = synchronized(lock) {
        runCatching {
            File(outboxRoot, "task_$taskId").deleteRecursively()
        }
        Unit
    }

    private fun isInsideDir(file: File, dir: File): Boolean {
        val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val dirPath = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return filePath.startsWith(dirPath + File.separator)
    }
}
