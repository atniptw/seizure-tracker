package com.atnip.seizuretracker.util

import com.atnip.seizuretracker.data.model.Seizure
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CsvExporterTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

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
    fun `buildCsv with no seizures is just the header`() {
        val csv = CsvExporter.buildCsv(emptyList())
        assertEquals(
            "Date/Time,Duration,Type,Symptoms,Signs before onset,Possible triggers," +
                "Recovery (min),Recovery behavior,Rescue med given,Rescue med details,Notes,Logged by",
            csv
        )
    }

    @Test
    fun `buildCsv escapes commas quotes and newlines`() {
        val seizure = Seizure(
            timestampMillis = 0L,
            notes = "line one\nline two, \"quoted\""
        )
        val csv = CsvExporter.buildCsv(listOf(seizure))
        // Checked against the full text (not a per-line split) because a legally-quoted CSV
        // field is allowed to contain the newline it's being escaped for.
        assertEquals(true, csv.contains("\"line one\nline two, \"\"quoted\"\"\""))
    }

    @Test
    fun `buildCsv leaves plain values unquoted`() {
        val seizure = Seizure(timestampMillis = 0L, seizureType = "Focal (partial)")
        val csv = CsvExporter.buildCsv(listOf(seizure))
        val dataRow = rows(csv)[1]
        // "Focal (partial)" has no comma/quote/newline, so it should not be wrapped in quotes.
        assertEquals(true, dataRow.split(",").any { it == "Focal (partial)" })
    }

    @Test
    fun `buildCsv maps rescue med given to Yes or No`() {
        val given = Seizure(timestampMillis = 0L, rescueMedGiven = true)
        val notGiven = Seizure(timestampMillis = 1L, rescueMedGiven = false)
        val csv = CsvExporter.buildCsv(listOf(given, notGiven))
        val dataRows = rows(csv).drop(1)
        assertEquals(true, dataRows[0].contains(",Yes,"))
        assertEquals(true, dataRows[1].contains(",No,"))
    }

    @Test
    fun `buildCsv joins symptoms with semicolons`() {
        val seizure = Seizure(
            timestampMillis = 0L,
            symptoms = listOf("Drooling / foaming", "Twitching")
        )
        val csv = CsvExporter.buildCsv(listOf(seizure))
        val dataRow = rows(csv)[1]
        assertEquals(true, dataRow.contains("Drooling / foaming; Twitching"))
    }

    @Test
    fun `buildCsv sorts rows ascending by timestamp`() {
        val earlier = Seizure(timestampMillis = 1_000L, notes = "earlier")
        val later = Seizure(timestampMillis = 2_000L, notes = "later")
        // Deliberately passed in reverse order to prove sorting happens inside buildCsv.
        val csv = CsvExporter.buildCsv(listOf(later, earlier))
        val dataRows = rows(csv).drop(1)
        assertEquals(true, dataRows[0].contains("earlier"))
        assertEquals(true, dataRows[1].contains("later"))
    }
}
