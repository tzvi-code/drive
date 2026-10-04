package com.tzvi.drivedownloadmonitor.util

import java.util.Locale
import kotlin.math.max

object Formatters {
    fun bytes(bytes: Long): String {
        val value = max(bytes, 0L).toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var index = 0
        var scaled = value
        while (scaled >= 1024.0 && index < units.lastIndex) {
            scaled /= 1024.0
            index++
        }
        return if (index == 0) {
            "${scaled.toLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", scaled, units[index])
        }
    }

    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    fun eta(seconds: Long?): String {
        if (seconds == null || seconds < 0L) return "—"
        val mins = seconds / 60L
        val secs = seconds % 60L
        return if (mins > 0L) {
            "${mins}m ${secs}s"
        } else {
            "${secs}s"
        }
    }
}
