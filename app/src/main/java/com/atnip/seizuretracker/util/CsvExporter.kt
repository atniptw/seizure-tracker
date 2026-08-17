package com.atnip.seizuretracker.util

import android.content.Context
import androidx.core.content.FileProvider
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.ui.common.Entry
import java.io.File

object CsvExporter {

    private fun escape(value: String): String {
        val needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n")
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    /**
     * Builds the CSV text for the given entries. Pure/no I/O so it's unit-testable directly.
     * Leading `Pet`/`Entry type` columns accommodate the heterogeneous seizure + health-note rows;
     * seizure-only columns are blank on health-note rows and vice versa.
     */
    internal fun buildCsv(pets: List<Pet>, entries: List<Entry>): String {
        val petNameById = pets.associateBy({ it.id }, { it.name })
        val header = listOf(
            "Pet", "Entry type", "Date/Time", "Duration", "Type / Description", "Symptoms",
            "Signs before onset", "Possible triggers", "Recovery (min)", "Recovery behavior",
            "Rescue med given", "Rescue med details", "Notes", "Logged by"
        ).joinToString(",")

        val rows = entries.sortedBy { it.timestampMillis }.map { entry ->
            val petName = petNameById[entry.petId].orEmpty()
            val fields = when (entry) {
                is Entry.SeizureEntry -> {
                    val s = entry.seizure
                    listOf(
                        petName,
                        "Seizure",
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
                    )
                }
                is Entry.NoteEntry -> {
                    val n = entry.note
                    listOf(
                        petName,
                        "Health note",
                        DateTimeUtils.formatDateTime(n.timestampMillis),
                        "",
                        n.description,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        n.notes,
                        n.loggedByName
                    )
                }
            }
            fields.joinToString(",") { escape(it) }
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    /** Builds a CSV file of the given entries in the app's cache dir and returns a shareable Uri. */
    fun export(context: Context, pets: List<Pet>, entries: List<Entry>): android.net.Uri {
        val csv = buildCsv(pets, entries)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "${ExportFilenames.build(pets, entries)}.csv")
        file.writeText(csv)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
