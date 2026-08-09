package com.atnip.seizuretracker.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    private val dateTimeFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun formatDateTime(millis: Long): String = dateTimeFormat.format(Date(millis))
    fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "—"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
