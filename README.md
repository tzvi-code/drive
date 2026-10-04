# DriveDownloadMonitor

DriveDownloadMonitor is an Android 8.0+ application written in Kotlin with Jetpack Compose.

It monitors Google Drive notifications (com.google.android.apps.docs) and supported Android Downloads-provider notifications, then correlates them with real local file-size changes in Downloads.

## Features
- Floating TYPE_APPLICATION_OVERLAY download widget.
- Live bytes, smoothed speed and ETA when the total size is known.
- Indeterminate animated progress when Drive does not expose a total size.
- Up to 3 concurrent downloads in the overlay.
- Pause/failed/cancelled state handling after stale writes or notification disappearance.
- Notification Listener reconnect with requestRebind().
- Foreground monitoring service.
- Light/dark system theme.
- First-run permission dashboard.
- Downloads-folder access through Storage Access Framework on modern Android.

## Build

The repository includes a GitHub Actions workflow that builds app-debug.apk on pushes and pull requests to main.

The project targets SDK 36 and requires Java 17 for the build.
