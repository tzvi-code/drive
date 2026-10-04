package com.tzvi.drivedownloadmonitor

import android.app.Application
import com.tzvi.drivedownloadmonitor.service.DownloadMonitorService

class DriveDownloadMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadMonitorService.createNotificationChannel(this)
    }
}
