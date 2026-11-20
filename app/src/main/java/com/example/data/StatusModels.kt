package com.example.data

import android.net.Uri
import java.util.concurrent.TimeUnit

enum class StatusSource(val shortLabel: String, val displayName: String) {
    WHATSAPP("WA", "WhatsApp"),
    WHATSAPP_BUSINESS("WA Biz", "WA Business"),
    CUSTOM_FOLDER("Folder", "Custom Folder"),
    SAVED_VAULT("Saved", "Saved Vault"),
    HISTORY_7D("7d History", "7 Days History")
}

enum class SourceFilter(val label: String) {
    ALL("All"),
    WHATSAPP("WhatsApp"),
    WHATSAPP_BUSINESS("WA Business"),
    CUSTOM_FOLDER("Folder")
}

enum class HistoryMediaFilter(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos")
}

enum class MediaType {
    IMAGE,
    VIDEO
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

data class StatusItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val source: StatusSource,
    val isSavedInVault: Boolean = false,
    val durationMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val originSource: StatusSource? = null
) {
    val isHistoryItem: Boolean
        get() = source == StatusSource.HISTORY_7D

    fun formattedSize(): String {
        if (sizeBytes <= 0L) return "0 KB"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format("%.1f MB", mb)
        } else {
            String.format("%.0f KB", kb.coerceAtLeast(1.0))
        }
    }

    fun formattedDuration(): String? {
        val ms = durationMillis ?: return null
        if (ms <= 0L) return null
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun formattedRemainingDuration(nowMillis: Long): String? {
        val expiry = expiresAtMillis ?: return null
        val remaining = expiry - nowMillis
        if (remaining <= 0L) return "Expired"

        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remaining).coerceAtLeast(1L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L

        return when {
            days >= 1L -> "${days}d ${hours}h left"
            hours >= 1L -> "${hours}h ${minutes}m left"
            else -> "${minutes}m left"
        }
    }

    fun isExpiringSoon(nowMillis: Long): Boolean {
        val expiry = expiresAtMillis ?: return false
        val remaining = expiry - nowMillis
        return remaining in 1L until TimeUnit.HOURS.toMillis(24L)
    }
}

data class ConnectedFolders(
    val whatsappUri: Uri? = null,
    val whatsappBusinessUri: Uri? = null,
    val customUri: Uri? = null
) {
    val hasAnyConnection: Boolean
        get() = whatsappUri != null || whatsappBusinessUri != null || customUri != null
}

sealed interface SaveResult {
    data class Saved(val itemName: String) : SaveResult
    data class AlreadyExists(val itemName: String) : SaveResult
    data class Failure(val reason: String) : SaveResult
}

data class BatchSaveResult(
    val newlySavedCount: Int,
    val alreadySavedCount: Int,
    val failedCount: Int
)
