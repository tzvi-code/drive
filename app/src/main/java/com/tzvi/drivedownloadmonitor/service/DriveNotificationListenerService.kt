package com.tzvi.drivedownloadmonitor.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tzvi.drivedownloadmonitor.data.DownloadStore
import java.util.Locale
import java.util.regex.Pattern

class DriveNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty().forEach(::handlePosted)
        DownloadMonitorService.startBestEffort(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching {
            requestRebind(
                android.content.ComponentName(this, DriveNotificationListenerService::class.java)
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isTargetPackage(sbn.packageName)) {
            val key = notificationKey(sbn)
            DownloadStore.markNotificationRemoved(key)
            DownloadMonitorService.startBestEffort(this)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (!isTargetPackage(sbn.packageName)) return

        val parsed = parseNotification(sbn) ?: return
        DownloadStore.upsertFromNotification(
            id = parsed.id,
            fileName = parsed.fileName,
            expectedBytes = parsed.expectedBytes,
            notificationProgress = parsed.progress,
            notificationPackage = sbn.packageName,
            notificationKey = notificationKey(sbn)
        )
        DownloadMonitorService.startDownload(
            context = this,
            id = parsed.id,
            fileName = parsed.fileName,
            expectedBytes = parsed.expectedBytes,
            progress = parsed.progress,
            packageName = sbn.packageName,
            notificationKey = notificationKey(sbn)
        )
    }

    private fun parseNotification(sbn: StatusBarNotification): ParsedDownload? {
        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val title = firstText(extras, Notification.EXTRA_TITLE)
        val text = firstText(extras, Notification.EXTRA_TEXT)
        val bigText = firstText(extras, Notification.EXTRA_BIG_TEXT)
        val combined = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" • ")
        val lowered = combined.lowercase(Locale.getDefault())

        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0).takeIf { progressMax > 0 }?.let {
            (it.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
        }

        val containsDownloadKeyword = DOWNLOAD_WORDS.any { lowered.contains(it) }
        val looksLikeFile = FILE_NAME_PATTERN.matcher(title).find() ||
            FILE_NAME_PATTERN.matcher(text).find()

        if (!containsDownloadKeyword && progress == null && !looksLikeFile) {
            return null
        }

        val filename = extractFileName(title, text)
        val expectedBytes = parseExpectedBytes(combined)
        val normalizedProgress = progress?.takeUnless { progressMax <= 0 }

        return ParsedDownload(
            id = "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}",
            fileName = filename,
            expectedBytes = expectedBytes,
            progress = normalizedProgress
        )
    }

    private fun extractFileName(title: String, text: String): String {
        val candidates = listOf(title, text)
            .flatMap { raw ->
                raw.split("•", ":", "
", "—", " - ")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
        return candidates.firstOrNull { candidate ->
            FILE_NAME_PATTERN.matcher(candidate).find()
        }?.let { candidate ->
            FILE_NAME_PATTERN.matcher(candidate).let { matcher ->
                if (matcher.find()) matcher.group(1) ?: candidate else candidate
            }
        } ?: title.ifBlank { text }.ifBlank { "Google Drive download" }
    }

    private fun parseExpectedBytes(text: String): Long? {
        val matcher = SIZE_PATTERN.matcher(text)
        if (!matcher.find()) return null
        return sizeToBytes(matcher.group(3), matcher.group(4))
            ?: sizeToBytes(matcher.group(1), matcher.group(2))
    }

    private fun sizeToBytes(value: String?, unit: String?): Long? {
        if (value == null || unit == null) return null
        val number = value.replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (unit.uppercase(Locale.US)) {
            "B" -> 1.0
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (number * multiplier).toLong().coerceAtLeast(0L)
    }

    private fun firstText(extras: Bundle, key: String): String =
        extras.getCharSequence(key)?.toString()?.trim().orEmpty()

    private fun isTargetPackage(packageName: String): Boolean =
        packageName == DRIVE_PACKAGE || packageName == DOWNLOADS_PROVIDER_PACKAGE

    private fun notificationKey(sbn: StatusBarNotification): String =
        "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}"

    private data class ParsedDownload(
        val id: String,
        val fileName: String,
        val expectedBytes: Long?,
        val progress: Float?
    )

    companion object {
        private const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        private const val DOWNLOADS_PROVIDER_PACKAGE = "com.android.providers.downloads"
        private val DOWNLOAD_WORDS = setOf(
            "download",
            "downloading",
            "downloaded",
            "הורדה",
            "מוריד",
            "הורד",
            "מכין להורדה"
        )
        private val FILE_NAME_PATTERN = Pattern.compile(
            """([A-Za-z0-9._()-א-ת]+\.[A-Za-z0-9]{1,12})"""
        )
        private val SIZE_PATTERN = Pattern.compile(
            """(?i)(d+(?:[.,]d+)?)s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB)(?:s*/s*(d+(?:[.,]d+)?)s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB))?"""
        )
    }
}
