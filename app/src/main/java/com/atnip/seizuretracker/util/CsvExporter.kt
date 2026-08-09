package com.atnip.seizuretracker.util

import android.content.Context
import androidx.core.content.FileProvider
import com.atnip.seizuretracker.data.model.Seizure
import java.io.File

object CsvExporter {

    private fun escape(value: String): String {
        val needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n")
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    /** Builds the CSV text for the given seizures. Pure/no I/O so it's unit-testable directly. */
    internal fun buildCsv(seizures: List<Seizure>): String {
        val header = listOf(
            "Date/Time", "Duration", "Type", "Symptoms", "Signs before onset",
            "Possible triggers", "Recovery (min)", "Recovery behavior",
            "Rescue med given", "Rescue med details", "Notes", "Logged by"
        ).joinToString(",")

        val rows = seizures.sortedBy { it.timestampMillis }.map { s ->
            listOf(
                DateTimeUtils.formatDateTime(s.timestampMillis),
                DateTimeUtils.formatDuration(s.durationSeconds),
                s.seizureType,
                s.symptoms.joinToString("; "),
                s.preSeizureSigns,
                s.possibleTriggers,
                s.recoveryMinutes?.toString().orEmpty(),
                s.recoveryNotes,
                if (s.rescueMedGiven) "Yes" else "No",
                s.rescueMedDetails,
                s.notes,
                s.loggedByName
            ).joinToString(",") { escape(it) }
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    /** Builds a CSV file of the given seizures in the app's cache dir and returns a shareable Uri. */
    fun export(context: Context, dogName: String, seizures: List<Seizure>): android.net.Uri {
        val csv = buildCsv(seizures)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = dogName.ifBlank { "dog" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(exportsDir, "${safeName}_seizure_log.csv")
        file.writeText(csv)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
