package com.tzvi.drivedownloadmonitor.data

import com.tzvi.drivedownloadmonitor.domain.model.DownloadItem
import com.tzvi.drivedownloadmonitor.domain.model.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadStore {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    @Synchronized
    fun upsertFromNotification(
        id: String,
        fileName: String,
        expectedBytes: Long?,
        notificationProgress: Float?,
        notificationPackage: String,
        notificationKey: String,
        now: Long = System.currentTimeMillis()
    ) {
        val existing = _items.value.firstOrNull { it.id == id }
        val reusable = existing?.takeIf {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
        }
        val item = if (reusable == null) {
            DownloadItem(
                id = id,
                fileName = fileName.ifBlank { "Google Drive download" },
                expectedBytes = expectedBytes,
                notificationProgress = notificationProgress,
                progressPercent = notificationProgress?.let { it * 100f },
                notificationPackage = notificationPackage,
                notificationKey = notificationKey,
                startedAtMillis = now,
                lastGrowthMillis = now
            )
        } else {
            reusable.copy(
                fileName = fileName.ifBlank { reusable.fileName },
                expectedBytes = expectedBytes ?: reusable.expectedBytes,
                notificationProgress = notificationProgress ?: reusable.notificationProgress,
                progressPercent = notificationProgress?.let { it * 100f }
                    ?: reusable.progressPercent,
                notificationPackage = notificationPackage.ifBlank { reusable.notificationPackage },
                notificationKey = notificationKey.ifBlank { reusable.notificationKey },
                notificationRemovedAtMillis = null,
                state = DownloadState.DOWNLOADING,
                errorMessage = null,
                finishedAtMillis = null
            )
        }
        replace(item)
    }

    @Synchronized
    fun updateMeasuredBytes(
        id: String,
        currentBytes: Long,
        speedBytesPerSecond: Long,
        etaSeconds: Long?,
        expectedBytes: Long?,
        now: Long = System.currentTimeMillis()
    ) {
        val existing = _items.value.firstOrNull { it.id == id } ?: return
        val grew = currentBytes > existing.currentBytes
        val progress = expectedBytes?.takeIf { it > 0L }?.let {
            (currentBytes.toDouble() / it.toDouble() * 100.0).coerceIn(0.0, 100.0).toFloat()
        }

        val finishedByBytes = expectedBytes != null && expectedBytes > 0L && currentBytes >= expectedBytes
        val state = when {
            finishedByBytes -> DownloadState.COMPLETED
            existing.state == DownloadState.CANCELLED || existing.state == DownloadState.FAILED -> existing.state
            now - existing.lastGrowthMillis > STALE_AFTER_MILLIS -> DownloadState.PAUSED
            else -> DownloadState.DOWNLOADING
        }

        replace(
            existing.copy(
                currentBytes = currentBytes.coerceAtLeast(existing.currentBytes),
                speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
                etaSeconds = etaSeconds,
                expectedBytes = expectedBytes ?: existing.expectedBytes,
                progressPercent = progress ?: existing.progressPercent,
                state = state,
                lastGrowthMillis = if (grew) now else existing.lastGrowthMillis,
                completedAtMillis = if (state == DownloadState.COMPLETED) {
                    existing.completedAtMillis ?: now
                } else {
                    null
                },
                finishedAtMillis = if (state == DownloadState.COMPLETED) {
                    existing.finishedAtMillis ?: now
                } else {
                    null
                },
                errorMessage = if (state == DownloadState.PAUSED) "אין שינוי בגודל הקובץ במשך 10 שניות" else null
            )
        )
    }

    @Synchronized
    fun markNotificationRemoved(notificationKey: String, now: Long = System.currentTimeMillis()) {
        val existing = _items.value.firstOrNull { it.notificationKey == notificationKey } ?: return
        if (existing.state == DownloadState.COMPLETED) return

        replace(existing.copy(notificationRemovedAtMillis = now))
    }

    @Synchronized
    fun reconcile(now: Long = System.currentTimeMillis()) {
        _items.value.forEach { item ->
            if (item.state == DownloadState.COMPLETED ||
                item.state == DownloadState.CANCELLED ||
                item.state == DownloadState.FAILED
            ) {
                return@forEach
            }

            val removedLongEnough =
                item.notificationRemovedAtMillis?.let { now - it >= REMOVED_GRACE_MILLIS } == true

            if (removedLongEnough) {
                val canAssumeCompleted =
                    item.currentBytes > 0L && now - item.lastGrowthMillis <= COMPLETION_STABLE_MILLIS
                val newState = if (canAssumeCompleted) {
                    DownloadState.COMPLETED
                } else {
                    DownloadState.CANCELLED
                }

                replace(
                    item.copy(
                        state = newState,
                        completedAtMillis = if (newState == DownloadState.COMPLETED) now else null,
                        finishedAtMillis = now,
                        errorMessage = if (newState == DownloadState.CANCELLED) {
                            "הורדה הסתיימה או בוטלה לפני שהקובץ הושלם"
                        } else {
                            null
                        }
                    )
                )
            }
        }
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    @Synchronized
    fun pruneFinishedOlderThan(retentionMillis: Long, now: Long = System.currentTimeMillis()) {
        _items.value = _items.value.filter { item ->
            val terminal = item.state == DownloadState.COMPLETED ||
                item.state == DownloadState.CANCELLED ||
                item.state == DownloadState.FAILED
            if (!terminal) return@filter true
            val finishedAt = item.finishedAtMillis ?: return@filter true
            now - finishedAt < retentionMillis
        }
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter {
            it.state != DownloadState.COMPLETED &&
                it.state != DownloadState.CANCELLED &&
                it.state != DownloadState.FAILED
        }
    }

    private fun replace(item: DownloadItem) {
        _items.value = (_items.value.filterNot { it.id == item.id } + item)
            .sortedWith(compareBy<DownloadItem> { it.state.ordinal }.thenBy { it.startedAtMillis })
    }

    private const val STALE_AFTER_MILLIS = 10_000L
    private const val REMOVED_GRACE_MILLIS = 2_500L
    private const val COMPLETION_STABLE_MILLIS = 2_000L
}
