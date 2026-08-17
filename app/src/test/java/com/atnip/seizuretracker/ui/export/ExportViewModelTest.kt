@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.export

import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.testutil.resetFileProviderCache
import com.atnip.seizuretracker.ui.common.Entry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val bear = Pet(id = "p1", name = "Bear")
    private val milo = Pet(id = "p2", name = "Milo")

    private val bearSeizure = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1_000L))
    private val bearNote = Entry.NoteEntry(HealthNote(petId = "p1", timestampMillis = 2_000L, description = "Limping"))
    private val miloSeizure = Entry.SeizureEntry(Seizure(petId = "p2", timestampMillis = 3_000L))
    private val allEntries = listOf(bearSeizure, bearNote, miloSeizure)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetFileProviderCache()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filterExportEntries with default options includes everything`() {
        val filtered = filterExportEntries(allEntries, ExportOptions())
        assertEquals(3, filtered.size)
    }

    @Test
    fun `filterExportEntries restricts to the selected pet`() {
        val filtered = filterExportEntries(allEntries, ExportOptions(petId = "p1"))
        assertEquals(listOf(bearSeizure, bearNote), filtered)
    }

    @Test
    fun `filterExportEntries excludes seizures or health notes per flag`() {
        val noSeizures = filterExportEntries(allEntries, ExportOptions(includeSeizures = false))
        assertEquals(listOf(bearNote), noSeizures)

        val noNotes = filterExportEntries(allEntries, ExportOptions(includeHealthNotes = false))
        assertEquals(listOf(bearSeizure, miloSeizure), noNotes)
    }

    @Test
    fun `filterExportEntries LAST_30 excludes entries older than 30 days`() {
        val now = 40L * 24 * 60 * 60 * 1000
        val recent = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = now - 10L * 24 * 60 * 60 * 1000))
        val old = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 0L))
        val filtered = filterExportEntries(listOf(recent, old), ExportOptions(dateRange = DateRangeOption.LAST_30), nowMillis = now)
        assertEquals(listOf(recent), filtered)
    }

    @Test
    fun `filterExportEntries CUSTOM uses the given start and end`() {
        val inRange = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 500L))
        val outOfRange = Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 5_000L))
        val filtered = filterExportEntries(
            listOf(inRange, outOfRange),
            ExportOptions(dateRange = DateRangeOption.CUSTOM, customStartMillis = 0L, customEndMillis = 1_000L)
        )
        assertEquals(listOf(inRange), filtered)
    }

    // These use CSV format, not PDF: Robolectric's PdfDocument shadow leaks "closed" state
    // globally across separate test classes in the same JVM fork (real PdfDocument.startPage()
    // calls after the first one anywhere in the suite throw "document is closed!"), so all real
    // PdfDocument exercise is deliberately confined to PdfExporterTest's single test method.
    // ExportViewModel's format dispatch itself is a trivial two-branch `when`, so CSV coverage
    // here plus PdfExporter's own thorough coverage is an acceptable trade-off.
    @Test
    fun `generate with a single pet produces a result matching the filtered entries`() = runBlocking {
        withTimeout(10000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(petId = "p1", format = ExportFormat.CSV) }

            viewModel.generate(context, listOf(bear, milo), allEntries, emptyList(), emptyList())

            val result = viewModel.result.awaitFirst { it != null }!!
            assertEquals(2, result.entryCount)
            assertEquals("Bear", result.petNames)
            assertEquals("text/csv", result.mimeType)
            assertTrue(result.fileName.endsWith(".csv"))
        }
    }

    @Test
    fun `generate with CSV format and all pets includes every entry`() = runBlocking {
        withTimeout(10000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(format = ExportFormat.CSV) }

            viewModel.generate(context, listOf(bear, milo), allEntries, emptyList(), emptyList())

            val result = viewModel.result.awaitFirst { it != null }!!
            assertEquals(3, result.entryCount)
            assertEquals("Bear, Milo", result.petNames)
            assertEquals("text/csv", result.mimeType)
            assertTrue(result.fileName.endsWith(".csv"))
        }
    }

    @Test
    fun `saveToDevice copies the generated file to the target Uri`() = runBlocking {
        withTimeout(10000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(format = ExportFormat.CSV) }
            viewModel.generate(context, listOf(bear), listOf(bearSeizure), emptyList(), emptyList())
            viewModel.result.awaitFirst { it != null }

            val targetFile = File(context.cacheDir, "saved-export.csv")
            val targetUri = android.net.Uri.fromFile(targetFile)

            // saveToDevice launches on Dispatchers.IO — a real thread pool untouched by the test
            // dispatcher — so completion needs a thread-safe flag, not a plain var (which Kotlin
            // won't even let be @Volatile as a local anyway).
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            viewModel.saveToDevice(context, targetUri) { done.set(true) }
            withTimeout(10000) {
                while (!done.get()) kotlinx.coroutines.yield()
            }

            assertTrue(targetFile.length() > 0)
        }
    }
}
