package com.example.data.history

import android.content.Context
import androidx.core.content.FileProvider
import com.example.data.MediaType
import com.example.data.StatusItem
import com.example.data.StatusSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class HistoryRepository(private val context: Context) {

    private val dao = StatusVaultDatabase.getInstance(context).historyStatusDao()
    private val syncMutex = Mutex()

    companion object {
        const val RETENTION_DAYS = 7L
        val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        private const val HISTORY_DIR_NAME = "status_history_7d"
    }

    private fun historyDirectory(): File {
        val dir = File(context.filesDir, HISTORY_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun observeHistoryItems(savedGalleryNames: Set<String>): Flow<List<StatusItem>> {
        return dao.observeAllHistory().map { entities ->
            val now = System.currentTimeMillis()
            entities.mapNotNull { entity ->
                if (entity.expiresAtMillis <= now) return@mapNotNull null
                val file = File(entity.localFilePath)
                if (!file.exists() || file.length() <= 0L) return@mapNotNull null
                val contentUri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrNull() ?: return@mapNotNull null

                val origin = StatusSource.entries.firstOrNull { it.name == entity.originSource }
                    ?: StatusSource.WHATSAPP
                val mediaType = if (entity.mediaType == MediaType.VIDEO.name) {
                    MediaType.VIDEO
                } else {
                    MediaType.IMAGE
                }

                StatusItem(
                    id = entity.id,
                    uri = contentUri,
                    name = entity.fileName,
                    mimeType = entity.mimeType,
                    mediaType = mediaType,
                    sizeBytes = entity.sizeBytes,
                    lastModifiedMillis = entity.statusModifiedMillis,
                    source = StatusSource.HISTORY_7D,
                    isSavedInVault = savedGalleryNames.contains(entity.fileName),
                    durationMillis = entity.durationMillis,
                    expiresAtMillis = entity.expiresAtMillis,
                    originSource = origin
                )
            }
        }
    }

    /**
     * Deletes any cached statuses whose 7-day retention window has elapsed, removing both
     * the Room metadata entry and the cached file on disk.
     */
    suspend fun purgeExpired(nowMillis: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val expired = dao.getExpiredEntries(nowMillis)
                if (expired.isNotEmpty()) {
                    expired.forEach { entity ->
                        runCatching { File(entity.localFilePath).delete() }
                    }
                    dao.deleteExpired(nowMillis)
                }
                // Also clean up any orphan files whose DB row is missing
                val validPaths = dao.getAllEntries().map { it.localFilePath }.toSet()
                historyDirectory().listFiles()?.forEach { file ->
                    if (file.absolutePath !in validPaths) {
                        runCatching { file.delete() }
                    }
                }
                expired.size
            }
        }

    /**
     * Automatically caches newly viewed statuses from the connected WhatsApp folders into
     * internal storage with a 7-day expiration timestamp.
     */
    suspend fun syncViewedStatuses(liveStatuses: List<StatusItem>): Int =
        withContext(Dispatchers.IO) {
            if (liveStatuses.isEmpty()) return@withContext 0
            syncMutex.withLock {
                val existingIds = dao.getAllIds().toHashSet()
                val dir = historyDirectory()
                var newlyArchived = 0

                for (item in liveStatuses) {
                    if (item.source == StatusSource.SAVED_VAULT || item.source == StatusSource.HISTORY_7D) {
                        continue
                    }
                    val stableKey = buildStableHistoryId(item)
                    if (stableKey in existingIds) continue

                    val safeFileName = "${System.currentTimeMillis()}_${item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                    val destFile = File(dir, safeFileName)

                    val copied = runCatching {
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output, bufferSize = 32 * 1024)
                            }
                        } != null && destFile.exists() && destFile.length() > 0L
                    }.getOrDefault(false)

                    if (copied) {
                        val now = System.currentTimeMillis()
                        val modified = item.lastModifiedMillis.takeIf { it > 0L } ?: now
                        val entity = HistoryStatusEntity(
                            id = stableKey,
                            fileName = item.name,
                            localFilePath = destFile.absolutePath,
                            mimeType = item.mimeType,
                            mediaType = item.mediaType.name,
                            sizeBytes = item.sizeBytes.takeIf { it > 0L } ?: destFile.length(),
                            originSource = item.source.name,
                            durationMillis = item.durationMillis,
                            statusModifiedMillis = modified,
                            savedAtMillis = now,
                            expiresAtMillis = now + RETENTION_MILLIS
                        )
                        dao.insert(entity)
                        existingIds.add(stableKey)
                        newlyArchived++
                    } else {
                        runCatching { destFile.delete() }
                    }
                }
                newlyArchived
            }
        }

    suspend fun deleteHistoryItems(items: List<StatusItem>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        syncMutex.withLock {
            val ids = items.map { it.id }
            val allEntries = dao.getAllEntries().associateBy { it.id }
            var deleted = 0
            for (id in ids) {
                allEntries[id]?.let { entity ->
                    runCatching { File(entity.localFilePath).delete() }
                    deleted++
                }
            }
            dao.deleteByIds(ids)
            deleted
        }
    }

    suspend fun clearAllHistory(): Unit = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            dao.deleteAll()
            historyDirectory().listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun buildStableHistoryId(item: StatusItem): String {
        return "hist:${item.source.name}:${item.name}:${item.sizeBytes}"
    }
}
