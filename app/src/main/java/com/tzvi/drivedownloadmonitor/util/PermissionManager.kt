package com.tzvi.drivedownloadmonitor.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tzvi.drivedownloadmonitor.service.DriveNotificationListenerService

data class PermissionSnapshot(
    val notificationListenerGranted: Boolean,
    val overlayGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val downloadsAccessGranted: Boolean,
    val legacyStorageGranted: Boolean,
    val batteryOptimizationIgnored: Boolean
) {
    val coreReady: Boolean
        get() = notificationListenerGranted && overlayGranted && downloadsAccessGranted
}

object PermissionManager {
    fun snapshot(context: Context): PermissionSnapshot {
        val appContext = context.applicationContext
        val notificationListener = isNotificationListenerEnabled(appContext)
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
        val postNotifications = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val downloads = hasPersistedDownloadsAccess(appContext)
        val legacyStorage = if (Build.VERSION.SDK_INT <= 32) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false
        } else {
            true
        }

        return PermissionSnapshot(
            notificationListenerGranted = notificationListener,
            overlayGranted = overlay,
            postNotificationsGranted = postNotifications,
            downloadsAccessGranted = downloads,
            legacyStorageGranted = legacyStorage,
            batteryOptimizationIgnored = batteryIgnored
        )
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, DriveNotificationListenerService::class.java)
        return enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == component
        }
    }

    fun hasPersistedDownloadsAccess(context: Context): Boolean {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DOWNLOADS_URI_KEY, null)
            ?: return false
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
    }

    fun saveDownloadsTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DOWNLOADS_URI_KEY, uri.toString())
            .apply()
    }

    fun getDownloadsTreeUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DOWNLOADS_URI_KEY, null)
            ?: return null
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return null
        return context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.uri == uri }
            ?.uri
    }

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                data = Uri.parse("package:${context.packageName}")
            }
        }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    private const val PREFS_NAME = "drive_download_monitor"
    private const val DOWNLOADS_URI_KEY = "downloads_tree_uri"
}
