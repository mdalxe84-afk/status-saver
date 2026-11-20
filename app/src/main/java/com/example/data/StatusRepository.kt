package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class StatusRepository(private val context: Context) {

    private val durationCache = ConcurrentHashMap<String, Long>()

    companion object {
        private const val VAULT_FOLDER_NAME = "StatusVault"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "3gp", "mov", "webm", "avi")
    }

    fun buildInitialPickerUri(source: StatusSource): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val relativePath = when (source) {
            StatusSource.WHATSAPP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
                } else {
                    "WhatsApp/Media/.Statuses"
                }
            }
            StatusSource.WHATSAPP_BUSINESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"
                } else {
                    "WhatsApp Business/Media/.Statuses"
                }
            }
            StatusSource.CUSTOM_FOLDER, StatusSource.SAVED_VAULT, StatusSource.HISTORY_7D -> return null
        }
        return runCatching {
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY,
                "primary:$relativePath"
            )
        }.getOrNull()
    }

    fun persistTreePermission(uri: Uri): Boolean {
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
    }

    fun releaseTreePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun hasPersistedReadPermission(uri: Uri): Boolean {
        val persisted = context.contentResolver.persistedUriPermissions
        return persisted.any { it.uri == uri && it.isReadPermission }
    }

    suspend fun loadSavedVaultItems(): List<StatusItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StatusItem>()
        items.addAll(querySavedMediaStore(isVideo = false))
        items.addAll(querySavedMediaStore(isVideo = true))
        items.sortedByDescending { it.lastModifiedMillis }
    }

    suspend fun loadLiveStatuses(
        folders: ConnectedFolders,
        savedNames: Set<String>
    ): List<StatusItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<StatusItem>()
        folders.whatsappUri?.let { uri ->
            if (hasPersistedReadPermission(uri)) {
                all.addAll(queryTreeStatuses(uri, StatusSource.WHATSAPP, savedNames))
            }
        }
        folders.whatsappBusinessUri?.let { uri ->
            if (hasPersistedReadPermission(uri)) {
                all.addAll(queryTreeStatuses(uri, StatusSource.WHATSAPP_BUSINESS, savedNames))
            }
        }
        folders.customUri?.let { uri ->
            if (hasPersistedReadPermission(uri)) {
                all.addAll(queryTreeStatuses(uri, StatusSource.CUSTOM_FOLDER, savedNames))
            }
        }
        all.sortedByDescending { it.lastModifiedMillis }
    }

    private fun queryTreeStatuses(
        treeUri: Uri,
        source: StatusSource,
        savedNames: Set<String>
    ): List<StatusItem> {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val directResults = queryChildrenByDocId(treeUri, treeDocId, source, savedNames)
        if (directResults.isNotEmpty() || source == StatusSource.CUSTOM_FOLDER) {
            return directResults
        }

        // Smart rescue: if user tapped "Use this folder" on WhatsApp or Media parent folder,
        // traverse down into Media/.Statuses automatically.
        val resolvedStatusesDocId = findStatusesSubfolderDocId(treeUri, treeDocId)
        return if (resolvedStatusesDocId != null) {
            queryChildrenByDocId(treeUri, resolvedStatusesDocId, source, savedNames)
        } else {
            emptyList()
        }
    }

    private fun findStatusesSubfolderDocId(treeUri: Uri, rootDocId: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        var mediaDocId: String? = null
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    if (name.equals(".Statuses", ignoreCase = true)) return docId
                    if (name.equals("Media", ignoreCase = true)) mediaDocId = docId
                }
            }
        }
        return mediaDocId?.let { findStatusesSubfolderDocId(treeUri, it) }
    }

    private fun queryChildrenByDocId(
        treeUri: Uri,
        parentDocId: String,
        source: StatusSource,
        savedNames: Set<String>
    ): List<StatusItem> {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val results = mutableListOf<StatusItem>()
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "status" else "status"
                    val rawMime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val lastModified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L

                    if (name.startsWith(".") || rawMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        continue
                    }

                    val ext = name.substringAfterLast('.', "").lowercase()
                    val mediaType = when {
                        rawMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> MediaType.IMAGE
                        rawMime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> MediaType.VIDEO
                        else -> null
                    } ?: continue

                    val effectiveMime = if (rawMime.isNotBlank() && rawMime != "application/octet-stream") {
                        rawMime
                    } else if (mediaType == MediaType.VIDEO) {
                        "video/mp4"
                    } else {
                        "image/jpeg"
                    }

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val duration = if (mediaType == MediaType.VIDEO) {
                        readVideoDurationCached(docUri)
                    } else {
                        null
                    }

                    results.add(
                        StatusItem(
                            id = "${source.name}:$docId",
                            uri = docUri,
                            name = name,
                            mimeType = effectiveMime,
                            mediaType = mediaType,
                            sizeBytes = size,
                            lastModifiedMillis = lastModified,
                            source = source,
                            isSavedInVault = savedNames.contains(name),
                            durationMillis = duration
                        )
                    )
                }
            }
        }
        return results
    }

    private fun querySavedMediaStore(isVideo: Boolean): List<StatusItem> {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (isVideo) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("%$VAULT_FOLDER_NAME%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("%/$VAULT_FOLDER_NAME/%")
        }

        val items = mutableListOf<StatusItem>()
        runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durCol = if (isVideo) cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "saved_status"
                    val mime = cursor.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val size = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val duration = if (durCol >= 0) cursor.getLong(durCol).takeIf { it > 0L } else null
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    items.add(
                        StatusItem(
                            id = "saved_${if (isVideo) "v" else "i"}_$id",
                            uri = contentUri,
                            name = name,
                            mimeType = mime,
                            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                            sizeBytes = size,
                            lastModifiedMillis = dateAddedSec * 1000L,
                            source = StatusSource.SAVED_VAULT,
                            isSavedInVault = true,
                            durationMillis = duration
                        )
                    )
                }
            }
        }
        return items
    }

    private fun readVideoDurationCached(uri: Uri): Long? {
        val key = uri.toString()
        durationCache[key]?.let { return it }
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (duration != null && duration > 0L) {
            durationCache[key] = duration
        }
        return duration
    }

    suspend fun saveStatusToGallery(item: StatusItem): SaveResult =
        copyStatusToPublicDcimFolder(item)

    /**
     * Copies the selected WhatsApp status media item (image or video) into the public
     * `DCIM/StatusVault` directory using scoped MediaStore on Android 10+ (zero storage
     * permissions required) and public DCIM + MediaScanner on Android 9 and below.
     */
    suspend fun copyStatusToPublicDcimFolder(item: StatusItem): SaveResult = withContext(Dispatchers.IO) {
        if (item.isSavedInVault) {
            return@withContext SaveResult.AlreadyExists(item.name)
        }

        val existingSaved = loadSavedVaultItems()
        if (existingSaved.any { it.name == item.name }) {
            return@withContext SaveResult.AlreadyExists(item.name)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyToDcimViaMediaStoreQPlus(item)
            } else {
                copyToDcimViaLegacyStorage(item)
            }
        }.fold(
            onSuccess = { saved ->
                if (saved) SaveResult.Saved(item.name)
                else SaveResult.Failure("Unable to copy media file to DCIM/StatusVault")
            },
            onFailure = { err ->
                SaveResult.Failure(err.localizedMessage ?: "Failed to copy status")
            }
        )
    }

    suspend fun saveBatch(items: List<StatusItem>): BatchSaveResult = withContext(Dispatchers.IO) {
        val existingNames = loadSavedVaultItems().map { it.name }.toMutableSet()
        var newlySaved = 0
        var alreadySaved = 0
        var failed = 0

        for (item in items) {
            if (item.isSavedInVault || existingNames.contains(item.name)) {
                alreadySaved++
                continue
            }
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    copyToDcimViaMediaStoreQPlus(item)
                } else {
                    copyToDcimViaLegacyStorage(item)
                }
            }.getOrDefault(false)

            if (ok) {
                newlySaved++
                existingNames.add(item.name)
            } else {
                failed++
            }
        }
        BatchSaveResult(newlySaved, alreadySaved, failed)
    }

    private fun copyToDcimViaMediaStoreQPlus(item: StatusItem): Boolean {
        val resolver = context.contentResolver
        val isVideo = item.mediaType == MediaType.VIDEO
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativeDir = "${Environment.DIRECTORY_DCIM}/$VAULT_FOLDER_NAME"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val destUri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openInputStream(item.uri)?.use { input ->
                resolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                } ?: return false
            } ?: return false

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(destUri, values, null, null)
            true
        } catch (t: Throwable) {
            resolver.delete(destUri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun copyToDcimViaLegacyStorage(item: StatusItem): Boolean {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val vaultDir = File(dcimDir, VAULT_FOLDER_NAME)
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            return false
        }
        val targetFile = File(vaultDir, item.name)
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        } ?: return false

        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(item.mimeType),
            null
        )
        return true
    }

    suspend fun deleteSavedItems(items: List<StatusItem>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        for (item in items) {
            if (item.source != StatusSource.SAVED_VAULT) continue
            val rows = runCatching {
                context.contentResolver.delete(item.uri, null, null)
            }.getOrDefault(0)
            if (rows > 0) deletedCount++
        }
        deletedCount
    }

    fun buildShareIntent(items: List<StatusItem>, targetPackage: String? = null): Intent? {
        if (items.isEmpty()) return null
        val mime = when {
            items.all { it.mediaType == MediaType.IMAGE } -> "image/*"
            items.all { it.mediaType == MediaType.VIDEO } -> "video/*"
            else -> "*/*"
        }

        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType
                putExtra(Intent.EXTRA_STREAM, items.first().uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val uris = ArrayList<Uri>(items.map { it.uri })
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        if (!targetPackage.isNullOrBlank()) {
            intent.setPackage(targetPackage)
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
            // Fallback to generic chooser if target package is not installed
            intent.setPackage(null)
        }
        return Intent.createChooser(intent, "Share status via")
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun openWhatsAppApp(): Intent? {
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
    }
}
