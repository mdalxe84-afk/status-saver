package com.example.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_statuses")
data class HistoryStatusEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val localFilePath: String,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val originSource: String,
    val durationMillis: Long?,
    val statusModifiedMillis: Long,
    val savedAtMillis: Long,
    val expiresAtMillis: Long
)
