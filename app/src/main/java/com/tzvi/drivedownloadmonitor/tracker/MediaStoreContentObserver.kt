package com.tzvi.drivedownloadmonitor.tracker

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.tzvi.drivedownloadmonitor.domain.model.DownloadItem
import com.tzvi.drivedownloadmonitor.util.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreContentObserver(
    private val context: Context,
    private val onChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val resolver: ContentResolver = context.contentResolver
    private var registered = false

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        onChanged()
    }

    fun register() {
        if (registered) return
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        resolver.registerContentObserver(uri, true, this)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        resolver.unregisterContentObserver(this)
        registered = false
    }
}

class MediaStoreDownloadTracker(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun measure(item: DownloadItem): MeasuredFile? = withContext(Dispatchers.IO) {
        val names = candidateNames(item.fileName)
        findInPersistedTree(names)?.let { return@withContext it }

        if (Build.VERSION.SDK_INT >= 29) {
            findInMediaStore(names)?.let { return@withContext it }
        }

        findInLegacyDownloads(names)
    }

    private fun candidateNames(fileName: String): List<String> {
        val cleaned = fileName.substringAfterLast('/').trim()
        val values = linkedSetOf(cleaned)
        for (suffix in TEMP_SUFFIXES) {
            values += "$cleaned$suffix"
        }
        if (cleaned.endsWith(".crdownload", true)) values += cleaned.removeSuffixIgnoreCase(".crdownload")
        if (cleaned.endsWith(".part", true)) values += cleaned.removeSuffixIgnoreCase(".part")
        if (cleaned.endsWith(".tmp", true)) values += cleaned.removeSuffixIgnoreCase(".tmp")
        return values.toList()
    }

    private fun findInPersistedTree(names: List<String>): MeasuredFile? {
        val treeUri = PermissionManager.getDownloadsTreeUri(appContext) ?: return null
        val tree = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        val children = runCatching { tree.listFiles() }.getOrNull() ?: return null
        val normalized = names.map { it.lowercase() }.toSet()

        val match = children.firstOrNull { child ->
            child.isFile && child.name?.lowercase() in normalized
        } ?: children.firstOrNull { child ->
            child.isFile && child.name?.lowercase()?.let { candidate ->
                normalized.any { expected ->
                    candidate.startsWith(expected) ||
                        candidate.removeSuffix(".crdownload") == expected ||
                        candidate.removeSuffix(".part") == expected
                }
            } == true
        } ?: return null

        val size = runCatching { match.length() }.getOrNull() ?: return null
        return MeasuredFile(
            displayName = match.name.orEmpty(),
            sizeBytes = size.coerceAtLeast(0L),
            source = match.uri
        )
    }

    private fun findInMediaStore(names: List<String>): MeasuredFile? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        names.forEach { name ->
            val result = runCatching {
                resolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(name),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    )
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                    MeasuredFile(
                        displayName = displayName,
                        sizeBytes = size.coerceAtLeast(0L),
                        source = Uri.withAppendedPath(collection, id.toString())
                    )
                }
            }.getOrNull()
            if (result != null) return result
        }

        return runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                var checked = 0
                while (cursor.moveToNext() && checked++ < 100) {
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    )
                    val lower = displayName.lowercase()
                    if (names.any { expected ->
                            lower == expected ||
                                lower.startsWith(expected) ||
                                lower.removeSuffix(".crdownload") == expected ||
                                lower.removeSuffix(".part") == expected
                        }
                    ) {
                        val id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        )
                        val size = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        )
                        return@use MeasuredFile(
                            displayName = displayName,
                            sizeBytes = size.coerceAtLeast(0L),
                            source = Uri.withAppendedPath(collection, id.toString())
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun findInLegacyDownloads(names: List<String>): MeasuredFile? {
        val directory = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val files = runCatching { directory.listFiles().orEmpty() }.getOrDefault(emptyArray())
        val normalized = names.map { it.lowercase() }

        val file = files.firstOrNull { candidate ->
            val lower = candidate.name.lowercase()
            normalized.any { expected ->
                lower == expected ||
                    lower.startsWith(expected) ||
                    lower.removeSuffix(".crdownload") == expected ||
                    lower.removeSuffix(".part") == expected
            }
        } ?: return null

        return MeasuredFile(
            displayName = file.name,
            sizeBytes = file.length().coerceAtLeast(0L),
            source = Uri.fromFile(file)
        )
    }

    data class MeasuredFile(
        val displayName: String,
        val sizeBytes: Long,
        val source: Uri
    )

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, true)) dropLast(suffix.length) else this

    companion object {
        private val TEMP_SUFFIXES = listOf(".crdownload", ".part", ".tmp")
    }
}
