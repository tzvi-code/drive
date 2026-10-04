package com.tzvi.drivedownloadmonitor.ui.main

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tzvi.drivedownloadmonitor.R
import com.tzvi.drivedownloadmonitor.util.PermissionSnapshot

@Composable
fun OnboardingScreen(
    permissions: PermissionSnapshot,
    onNotificationListenerClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onNotificationPermissionClick: () -> Unit,
    onLegacyStorageClick: () -> Unit,
    onDownloadsFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "מד התקדמות אמיתי להורדות Google Drive",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.privacy_note),
            style = MaterialTheme.typography.bodyMedium
        )

        PermissionCard(
            title = stringResource(R.string.notification_access_title),
            description = stringResource(R.string.notification_access_description),
            granted = permissions.notificationListenerGranted,
            icon = Icons.Default.NotificationsActive,
            action = onNotificationListenerClick
        )

        PermissionCard(
            title = stringResource(R.string.overlay_title),
            description = stringResource(R.string.overlay_description),
            granted = permissions.overlayGranted,
            icon = Icons.Default.Settings,
            action = onOverlayClick
        )

        PermissionCard(
            title = stringResource(R.string.post_notifications_title),
            description = stringResource(R.string.post_notifications_description),
            granted = permissions.postNotificationsGranted,
            icon = Icons.Default.Notifications,
            action = onNotificationPermissionClick,
        )

        PermissionCard(
            title = stringResource(R.string.downloads_access_title),
            description = stringResource(R.string.downloads_access_description),
            granted = permissions.downloadsAccessGranted,
            icon = Icons.Default.Download,
            action = onDownloadsFolderClick
        )

        PermissionCard(
            title = stringResource(R.string.legacy_storage_title),
            description = stringResource(R.string.legacy_storage_description),
            granted = permissions.legacyStorageGranted,
            icon = Icons.Default.Download,
            action = onLegacyStorageClick,
            visible = Build.VERSION.SDK_INT <= 32
        )

        PermissionCard(
            title = stringResource(R.string.battery_title),
            description = stringResource(R.string.battery_description),
            granted = permissions.batteryOptimizationIgnored,
            icon = Icons.Default.BatterySaver,
            action = onBatteryOptimizationClick
        )

        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.monitoring_status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (permissions.coreReady) {
                        stringResource(R.string.ready)
                    } else {
                        stringResource(R.string.not_ready)
                    },
                    color = if (permissions.coreReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartMonitoring,
                        modifier = Modifier.weight(1f),
                        enabled = permissions.overlayGranted
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text(stringResource(R.string.start_monitor))
                    }
                    OutlinedButton(
                        onClick = onStopMonitoring,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text(stringResource(R.string.stop_monitor))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit,
    visible: Boolean = true
) {
    if (!visible) return

    Card(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = action, enabled = !granted) {
                    Text(
                        if (granted) {
                            stringResource(R.string.granted)
                        } else {
                            stringResource(R.string.open_settings)
                        }
                    )
                }
            }
        }
    }
}
