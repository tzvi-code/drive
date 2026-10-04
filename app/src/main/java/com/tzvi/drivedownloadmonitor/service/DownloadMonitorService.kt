package com.tzvi.drivedownloadmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tzvi.drivedownloadmonitor.R
import com.tzvi.drivedownloadmonitor.data.DownloadStore
import com.tzvi.drivedownloadmonitor.domain.model.DownloadItem
import com.tzvi.drivedownloadmonitor.domain.model.DownloadState
import com.tzvi.drivedownloadmonitor.tracker.MediaStoreContentObserver
import com.tzvi.drivedownloadmonitor.tracker.MediaStoreDownloadTracker
import com.tzvi.drivedownloadmonitor.ui.main.MainActivity
import com.tzvi.drivedownloadmonitor.ui.overlay.OverlayWindowManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlayManager: OverlayWindowManager
    private lateinit var contentObserver: MediaStoreContentObserver
    private lateinit var tracker: MediaStoreDownloadTracker
    private var samplingJob: Job? = null
    private val previousSamples = ConcurrentHashMap<String, Sample>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        startAsForeground()
        overlayManager = OverlayWindowManager(this)
        tracker = MediaStoreDownloadTracker(this)
        contentObserver = MediaStoreContentObserver(this) {
            requestImmediateSample()
        }
        contentObserver.register()
        observeStore()
        startSamplingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> handleStartDownload(intent)
            ACTION_NOTIFICATION_REMOVED -> {
                intent.getStringExtra(EXTRA_NOTIFICATION_KEY)?.let {
                    DownloadStore.markNotificationRemoved(it)
                }
            }
            ACTION_DISMISS_OVERLAY -> overlayManager.hide()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        contentObserver.unregister()
        overlayManager.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStartDownload(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val expectedBytes = intent.getLongExtra(EXTRA_EXPECTED_BYTES, -1L).takeIf { it >= 0L }
        val notificationProgress = intent.getFloatExtra(EXTRA_NOTIFICATION_PROGRESS, -1f)
            .takeIf { it in 0f..1f }

        DownloadStore.upsertFromNotification(
            id = id,
            fileName = fileName,
            expectedBytes = expectedBytes,
            notificationProgress = notificationProgress,
            notificationPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
            notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
        )
    }

    private fun observeStore() {
        serviceScope.launch {
            DownloadStore.items.collectLatest { items ->
                if (items.isEmpty()) {
                    overlayManager.hide()
                } else {
                    val now = System.currentTimeMillis()
                    val visible = items.filter {
                        it.state != DownloadState.COMPLETED ||
                            (it.finishedAtMillis ?: 0L) > now - FINISHED_RETENTION_MILLIS
                    }.take(MAX_OVERLAY_ITEMS)
                    if (visible.isEmpty()) overlayManager.hide() else overlayManager.show(visible)
                }
            }
        }
    }

    private fun startSamplingLoop() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                val items = DownloadStore.items.value
                items.filter {
                    it.state != DownloadState.COMPLETED &&
                        it.state != DownloadState.CANCELLED &&
                        it.state != DownloadState.FAILED
                }.forEach { item ->
                    updateMeasuredItem(item)
                }
                DownloadStore.reconcile()
                DownloadStore.pruneFinishedOlderThan(FINISHED_RETENTION_MILLIS)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private suspend fun updateMeasuredItem(item: DownloadItem) {
        val measured = tracker.measure(item)
        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = previousSamples[item.id]

        if (measured == null) {
            if (previous != null) {
                val noGrowthMillis = nowElapsed - previous.elapsedRealtime
                if (noGrowthMillis >= STALE_AFTER_MILLIS) {
                    DownloadStore.updateMeasuredBytes(
                        id = item.id,
                        currentBytes = previous.bytes,
                        speedBytesPerSecond = 0L,
                        etaSeconds = null,
                        expectedBytes = item.expectedBytes
                    )
                }
            }
            return
        }

        val deltaBytes = max(0L, measured.sizeBytes - (previous?.bytes ?: measured.sizeBytes))
        val deltaMillis = max(1L, nowElapsed - (previous?.elapsedRealtime ?: nowElapsed))
        val instantSpeed = if (previous == null) 0L else {
            ((deltaBytes.toDouble() / deltaMillis.toDouble()) * 1000.0).toLong()
        }
        val smoothedSpeed = if (previous == null) {
            0L
        } else {
            ((previous.speedBytesPerSecond * 0.65) + (instantSpeed * 0.35)).toLong()
        }

        val expected = item.expectedBytes
        val remaining = expected?.minus(measured.sizeBytes)?.coerceAtLeast(0L)
        val eta = if (smoothedSpeed > 0L && remaining != null) {
            (remaining.toDouble() / smoothedSpeed.toDouble()).toLong()
        } else {
            null
        }

        DownloadStore.updateMeasuredBytes(
            id = item.id,
            currentBytes = measured.sizeBytes,
            speedBytesPerSecond = smoothedSpeed,
            etaSeconds = eta,
            expectedBytes = expected
        )

        previousSamples[item.id] = Sample(
            bytes = measured.sizeBytes,
            speedBytesPerSecond = smoothedSpeed,
            elapsedRealtime = nowElapsed
        )
    }

    private fun requestImmediateSample() {
        serviceScope.launch(Dispatchers.Default) {
            DownloadStore.items.value.forEach { item ->
                if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PAUSED) {
                    updateMeasuredItem(item)
                }
            }
        }
    }

    private fun startAsForeground() {
        val notification = createMonitoringNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START_DOWNLOAD = "com.tzvi.drivedownloadmonitor.START_DOWNLOAD"
        const val ACTION_NOTIFICATION_REMOVED = "com.tzvi.drivedownloadmonitor.NOTIFICATION_REMOVED"
        const val ACTION_DISMISS_OVERLAY = "com.tzvi.drivedownloadmonitor.DISMISS_OVERLAY"

        const val EXTRA_ID = "extra_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_EXPECTED_BYTES = "extra_expected_bytes"
        const val EXTRA_NOTIFICATION_PROGRESS = "extra_notification_progress"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_NOTIFICATION_KEY = "extra_notification_key"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "drive_download_monitor"
        private const val SAMPLE_INTERVAL_MS = 450L
        private const val STALE_AFTER_MILLIS = 10_000L
        private const val MAX_OVERLAY_ITEMS = 3
        private const val FINISHED_RETENTION_MILLIS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, DownloadMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun startBestEffort(
            context: Context,
            fileName: String? = null,
            expectedBytes: Long? = null
        ) {
            try {
                val intent = Intent(context, DownloadMonitorService::class.java)
                fileName?.let { intent.putExtra(EXTRA_FILE_NAME, it) }
                expectedBytes?.let { intent.putExtra(EXTRA_EXPECTED_BYTES, it) }
                if (Build.VERSION.SDK_INT >= 26) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // The notification listener still receives events; the user can start monitoring
                // from the setup screen if background FGS launch is denied by the OS.
            }
        }

        fun startDownload(
            context: Context,
            id: String,
            fileName: String,
            expectedBytes: Long?,
            progress: Float?,
            packageName: String,
            notificationKey: String
        ) {
            val intent = Intent(context, DownloadMonitorService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
                expectedBytes?.let { putExtra(EXTRA_EXPECTED_BYTES, it) }
                progress?.let { putExtra(EXTRA_NOTIFICATION_PROGRESS, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                DownloadStore.upsertFromNotification(
                    id = id,
                    fileName = fileName,
                    expectedBytes = expectedBytes,
                    notificationProgress = progress,
                    notificationPackage = packageName,
                    notificationKey = notificationKey
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadMonitorService::class.java))
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.monitor_channel_description)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        private fun createMonitoringNotification(context: Context): Notification {
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.monitoring_notification_title))
                .setContentText(context.getString(R.string.monitoring_notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }

        private data class Sample(
            val bytes: Long,
            val speedBytesPerSecond: Long,
            val elapsedRealtime: Long
        )
    }
}
