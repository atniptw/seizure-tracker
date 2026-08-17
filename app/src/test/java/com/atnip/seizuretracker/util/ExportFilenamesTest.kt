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

class ExportFilenamesTest {

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

    private fun millisFor(year: Int, month: Int, day: Int): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 12, 0)
        return cal.timeInMillis
    }

    @Test
    fun `one pet, entries within a single month`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"))
        val entries = listOf(
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 8, 3))),
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 8, 20)))
        )
        assertEquals("Bear_seizure_report_Aug2026", ExportFilenames.build(pets, entries))
    }

    @Test
    fun `one pet, entries spanning months in the same year`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"))
        val entries = listOf(
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 6, 1))),
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 8, 20)))
        )
        assertEquals("Bear_seizure_report_Jun-Aug2026", ExportFilenames.build(pets, entries))
    }

    @Test
    fun `one pet, entries spanning different years`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"))
        val entries = listOf(
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2025, 12, 1))),
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 2, 15)))
        )
        assertEquals("Bear_seizure_report_Dec2025-Feb2026", ExportFilenames.build(pets, entries))
    }

    @Test
    fun `multiple pets join their names and drop the seizure_report suffix`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"), Pet(id = "p2", name = "Milo"))
        val entries = listOf(
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 8, 3)))
        )
        assertEquals("Bear_Milo_report_Aug2026", ExportFilenames.build(pets, entries))
    }

    @Test
    fun `sanitizes pet names with unsafe filename characters`() {
        val pets = listOf(Pet(id = "p1", name = "Bear/Max"))
        val entries = listOf(
            Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = millisFor(2026, 8, 3)))
        )
        assertEquals("Bear_Max_seizure_report_Aug2026", ExportFilenames.build(pets, entries))
    }

    @Test
    fun `no entries falls back to the current month`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"))
        assertEquals(
            "Bear_seizure_report_Aug2026",
            ExportFilenames.build(pets, emptyList(), nowMillis = millisFor(2026, 8, 10))
        )
    }

    @Test
    fun `health note entries also feed the range token`() {
        val pets = listOf(Pet(id = "p1", name = "Bear"))
        val entries = listOf(
            Entry.NoteEntry(HealthNote(petId = "p1", timestampMillis = millisFor(2026, 8, 3)))
        )
        assertEquals("Bear_seizure_report_Aug2026", ExportFilenames.build(pets, entries))
    }
}
