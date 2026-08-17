package com.atnip.seizuretracker.util

import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.ui.common.Entry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the base file name (no extension) shared by [PdfExporter] and [CsvExporter], e.g.
 * "Bear_seizure_report_Aug2026" for one pet, or "Bear_Milo_report_Jun-Aug2026" for several.
 */
object ExportFilenames {

    private val monthYear = SimpleDateFormat("MMMyyyy", Locale.US)
    private val monthOnly = SimpleDateFormat("MMM", Locale.US)

    fun build(pets: List<Pet>, entries: List<Entry>, nowMillis: Long = System.currentTimeMillis()): String {
        val petPart = when {
            pets.isEmpty() -> "Pet"
            pets.size == 1 -> sanitize(pets[0].name.ifBlank { "Pet" })
            else -> pets.joinToString("_") { sanitize(it.name.ifBlank { "Pet" }) }
        }
        val suffix = if (pets.size <= 1) "seizure_report" else "report"
        return "${petPart}_${suffix}_${rangeToken(entries, nowMillis)}"
    }

    private fun rangeToken(entries: List<Entry>, nowMillis: Long): String {
        if (entries.isEmpty()) return sanitize(monthYear.format(Date(nowMillis)))
        val start = Date(entries.minOf { it.timestampMillis })
        val end = Date(entries.maxOf { it.timestampMillis })
        val startYear = SimpleDateFormat("yyyy", Locale.US).format(start)
        val endYear = SimpleDateFormat("yyyy", Locale.US).format(end)
        val token = when {
            monthYear.format(start) == monthYear.format(end) -> monthYear.format(end)
            startYear == endYear -> "${monthOnly.format(start)}-${monthOnly.format(end)}$endYear"
            else -> "${monthOnly.format(start)}$startYear-${monthOnly.format(end)}$endYear"
        }
        return sanitize(token)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
