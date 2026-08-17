package com.atnip.seizuretracker.ui.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.ui.common.Entry
import com.atnip.seizuretracker.util.CsvExporter
import com.atnip.seizuretracker.util.ExportFilenames
import com.atnip.seizuretracker.util.PdfExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ExportFormat { PDF, CSV }

enum class DateRangeOption(val label: String) {
    LAST_30("Last 30 days"),
    LAST_90("Last 90 days"),
    ALL_TIME("All time"),
    CUSTOM("Custom")
}

/** [petId] `== null` means every pet ("All pets") rather than one specific pet. */
data class ExportOptions(
    val petId: String? = null,
    val dateRange: DateRangeOption = DateRangeOption.ALL_TIME,
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null,
    val includeSeizures: Boolean = true,
    val includeHealthNotes: Boolean = true,
    val format: ExportFormat = ExportFormat.PDF
)

data class ExportResult(
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
    val entryCount: Int,
    val petNames: String
)

/** Filters [entries] by [options] — pet, date range, and include-flags. Pure/testable. */
internal fun filterExportEntries(
    entries: List<Entry>,
    options: ExportOptions,
    nowMillis: Long = System.currentTimeMillis()
): List<Entry> {
    val range: LongRange? = when (options.dateRange) {
        DateRangeOption.ALL_TIME -> null
        DateRangeOption.LAST_30 -> (nowMillis - 30L * 24 * 60 * 60 * 1000)..nowMillis
        DateRangeOption.LAST_90 -> (nowMillis - 90L * 24 * 60 * 60 * 1000)..nowMillis
        DateRangeOption.CUSTOM -> (options.customStartMillis ?: 0L)..(options.customEndMillis ?: nowMillis)
    }
    return entries.filter { entry ->
        val petMatches = options.petId == null || entry.petId == options.petId
        val rangeMatches = range == null || entry.timestampMillis in range
        val typeMatches = when (entry) {
            is Entry.SeizureEntry -> options.includeSeizures
            is Entry.NoteEntry -> options.includeHealthNotes
        }
        petMatches && rangeMatches && typeMatches
    }
}

/**
 * Holds export configuration and the generated file across the export-options screen (16) → the
 * export-ready screen (17). Generation is local file I/O (PDF/CSV rendering), not Firestore, so
 * there's no repository — [PdfExporter]/[CsvExporter] are called directly.
 */
class ExportViewModel : ViewModel() {

    private val _options = MutableStateFlow(ExportOptions())
    val options: StateFlow<ExportOptions> = _options.asStateFlow()

    private val _result = MutableStateFlow<ExportResult?>(null)
    val result: StateFlow<ExportResult?> = _result.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    fun updateOptions(update: (ExportOptions) -> ExportOptions) {
        _options.value = update(_options.value)
    }

    /** Clears a previous result — called when navigating back from the ready screen to options. */
    fun clearResult() {
        _result.value = null
    }

    fun generate(
        context: Context,
        pets: List<Pet>,
        entries: List<Entry>,
        vets: List<Vet>,
        links: List<PetVetLink>,
        onDone: () -> Unit = {}
    ) {
        val opts = _options.value
        val selectedPets = opts.petId?.let { id -> pets.filter { it.id == id } } ?: pets
        val filtered = filterExportEntries(entries, opts)
        // Runs on the ambient (Main) dispatcher, same as every other ViewModel's
        // launch { repo.suspendFn(); onDone() } — this household's exports are at most a few
        // hundred entries, fast enough that a background dispatch isn't worth the complexity
        // (onDone() is what the screen uses to navigate, and NavController needs the real
        // main thread; an explicit Dispatchers.Default hop here would need to redispatch back).
        viewModelScope.launch {
            _generating.value = true
            val uri = when (opts.format) {
                ExportFormat.PDF -> PdfExporter.export(context, selectedPets, filtered, vets, links)
                ExportFormat.CSV -> CsvExporter.export(context, selectedPets, filtered)
            }
            val extension = if (opts.format == ExportFormat.PDF) "pdf" else "csv"
            val mimeType = if (opts.format == ExportFormat.PDF) "application/pdf" else "text/csv"
            _result.value = ExportResult(
                uri = uri,
                mimeType = mimeType,
                fileName = "${ExportFilenames.build(selectedPets, filtered)}.$extension",
                entryCount = filtered.size,
                petNames = selectedPets.joinToString(", ") { it.name }
            )
            _generating.value = false
            onDone()
        }
    }

    /** Copies the generated file to a user-chosen Uri (from the Storage Access Framework's CreateDocument). */
    fun saveToDevice(context: Context, targetUri: Uri, onDone: () -> Unit = {}) {
        val source = _result.value?.uri ?: return
        viewModelScope.launch {
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(targetUri)?.use { output -> input.copyTo(output) }
            }
            onDone()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ExportViewModel() as T
            }
        }
    }
}
