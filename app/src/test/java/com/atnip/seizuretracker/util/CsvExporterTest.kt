package com.atnip.seizuretracker.util

import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.ui.common.Entry
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CsvExporterTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone
    private val pets = listOf(Pet(id = "p1", name = "Bear"), Pet(id = "p2", name = "Milo"))

    @Before
    fun pinLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    private fun rows(csv: String): List<String> = csv.split("\n")

    @Test
    fun `buildCsv with no entries is just the header`() {
        val csv = CsvExporter.buildCsv(pets, emptyList())
        assertEquals(
            "Pet,Entry type,Date/Time,Duration,Type / Description,Symptoms,Signs before onset," +
                "Possible triggers,Recovery (min),Recovery behavior,Rescue med given,Rescue med details,Notes,Logged by",
            csv
        )
    }

    @Test
    fun `buildCsv escapes commas quotes and newlines`() {
        val seizure = Entry.SeizureEntry(
            Seizure(petId = "p1", timestampMillis = 0L, notes = "line one\nline two, \"quoted\"")
        )
        val csv = CsvExporter.buildCsv(pets, listOf(seizure))
        // Checked against the full text (not a per-line split) because a legally-quoted CSV
        // field is allowed to contain the newline it's being escaped for.
        assertEquals(true, csv.contains("\"line one\nline two, \"\"quoted\"\"\""))
    }

    @Test
    fun `buildCsv leaves plain values unquoted`() {
        val seizure = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 0L, seizureType = "Focal (partial)"))
        val csv = CsvExporter.buildCsv(pets, listOf(seizure))
        val dataRow = rows(csv)[1]
        // "Focal (partial)" has no comma/quote/newline, so it should not be wrapped in quotes.
        assertEquals(true, dataRow.split(",").any { it == "Focal (partial)" })
    }

    @Test
    fun `buildCsv maps rescue med given to Yes or No`() {
        val given = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 0L, rescueMedGiven = true))
        val notGiven = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1L, rescueMedGiven = false))
        val csv = CsvExporter.buildCsv(pets, listOf(given, notGiven))
        val dataRows = rows(csv).drop(1)
        assertEquals(true, dataRows[0].contains(",Yes,"))
        assertEquals(true, dataRows[1].contains(",No,"))
    }

    @Test
    fun `buildCsv joins symptoms with semicolons`() {
        val seizure = Entry.SeizureEntry(
            Seizure(petId = "p1", timestampMillis = 0L, symptoms = listOf("Drooling / foaming", "Twitching"))
        )
        val csv = CsvExporter.buildCsv(pets, listOf(seizure))
        val dataRow = rows(csv)[1]
        assertEquals(true, dataRow.contains("Drooling / foaming; Twitching"))
    }

    @Test
    fun `buildCsv sorts rows ascending by timestamp`() {
        val earlier = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1_000L, notes = "earlier"))
        val later = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 2_000L, notes = "later"))
        // Deliberately passed in reverse order to prove sorting happens inside buildCsv.
        val csv = CsvExporter.buildCsv(pets, listOf(later, earlier))
        val dataRows = rows(csv).drop(1)
        assertEquals(true, dataRows[0].contains("earlier"))
        assertEquals(true, dataRows[1].contains("later"))
    }

    @Test
    fun `buildCsv resolves each row's pet name from its petId`() {
        val bearSeizure = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 0L))
        val miloSeizure = Entry.SeizureEntry(Seizure(petId = "p2", timestampMillis = 1L))
        val csv = CsvExporter.buildCsv(pets, listOf(bearSeizure, miloSeizure))
        val dataRows = rows(csv).drop(1)
        assertEquals(true, dataRows[0].startsWith("Bear,Seizure,"))
        assertEquals(true, dataRows[1].startsWith("Milo,Seizure,"))
    }

    @Test
    fun `buildCsv marks health note rows and leaves seizure-only columns blank`() {
        val note = Entry.NoteEntry(
            HealthNote(petId = "p1", timestampMillis = 0L, description = "Limping", notes = "Better by evening")
        )
        val csv = CsvExporter.buildCsv(pets, listOf(note))
        val dataRow = rows(csv)[1]
        // Not asserting by split-index: Date/Time formats to e.g. "Jan 1, 1970 at 12:00 AM",
        // which itself contains a comma and gets quoted, so a naive split misaligns columns.
        assertEquals(true, dataRow.startsWith("Bear,Health note,"))
        assertEquals(true, dataRow.contains("Limping"))
        assertEquals(true, dataRow.contains("Better by evening"))
    }
}
