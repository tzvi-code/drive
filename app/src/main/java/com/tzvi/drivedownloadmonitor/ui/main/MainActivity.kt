package com.tzvi.drivedownloadmonitor.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tzvi.drivedownloadmonitor.service.DownloadMonitorService
import com.tzvi.drivedownloadmonitor.util.PermissionManager

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            val configuration = LocalConfiguration.current
            val isDark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val permissions by viewModel.permissions.collectAsState()

            val notificationPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    viewModel.refresh()
                }

            val legacyStorageLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    viewModel.refresh()
                }

            val folderLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                        }
                        PermissionManager.saveDownloadsTreeUri(this@MainActivity, uri)
                        viewModel.refresh()
                    }
                }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refresh()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MaterialTheme(
                colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
            ) {
                Surface {
                    OnboardingScreen(
                        permissions = permissions,
                        onNotificationListenerClick = {
                            startActivity(PermissionManager.notificationListenerSettingsIntent())
                        },
                        onOverlayClick = {
                            startActivity(PermissionManager.overlaySettingsIntent(this))
                        },
                        onNotificationPermissionClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        },
                        onLegacyStorageClick = {
                            if (Build.VERSION.SDK_INT <= 32 &&
                                ActivityCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyStorageLauncher.launch(
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                )
                            }
                        },
                        onDownloadsFolderClick = {
                            folderLauncher.launch(null)
                        },
                        onBatteryOptimizationClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    startActivity(PermissionManager.batteryOptimizationIntent(this))
                                } catch (_: Exception) {
                                    startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    )
                                }
                            }
                        },
                        onStartMonitoring = {
                            DownloadMonitorService.start(this)
                        },
                        onStopMonitoring = {
                            DownloadMonitorService.stop(this)
                        }
                    )
                }
            }
        }
    }
}
