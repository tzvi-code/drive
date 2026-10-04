package com.tzvi.drivedownloadmonitor.domain.model

enum class DownloadState {
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadItem(
    val id: String,
    val fileName: String,
    val expectedBytes: Long? = null,
    val currentBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val progressPercent: Float? = null,
    val notificationProgress: Float? = null,
    val state: DownloadState = DownloadState.DOWNLOADING,
    val notificationPackage: String = "",
    val notificationKey: String = "",
    val startedAtMillis: Long = System.currentTimeMillis(),
    val lastGrowthMillis: Long = System.currentTimeMillis(),
    val notificationRemovedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val errorMessage: String? = null
) {
    val isIndeterminate: Boolean
        get() = expectedBytes == null && progressPercent == null && notificationProgress == null

    val effectiveProgressPercent: Float?
        get() = when {
            expectedBytes != null && expectedBytes > 0L -> {
                (currentBytes.toDouble() / expectedBytes.toDouble() * 100.0)
                    .coerceIn(0.0, 100.0)
                    .toFloat()
            }
            notificationProgress != null -> notificationProgress
            else -> null
        }
}
