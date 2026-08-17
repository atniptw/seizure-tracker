package com.atnip.seizuretracker.ui.export

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.Entry
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.ui.common.PillChipSelector
import com.atnip.seizuretracker.ui.common.SegmentedControl
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.ui.vet.VetViewModel
import com.atnip.seizuretracker.util.DateTimeUtils
import java.util.Calendar

private const val PET_ALL_LABEL = "All pets"

/** Screen 16 — pet, date range, what to include, and format, before generating the file. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    navController: NavController,
    petListViewModel: PetListViewModel,
    seizureListViewModel: SeizureListViewModel,
    healthNoteListViewModel: HealthNoteListViewModel,
    vetViewModel: VetViewModel,
    exportViewModel: ExportViewModel
) {
    val pets by petListViewModel.pets.collectAsState()
    val seizures by seizureListViewModel.seizures.collectAsState()
    val healthNotes by healthNoteListViewModel.healthNotes.collectAsState()
    val vets by vetViewModel.vets.collectAsState()
    val links by vetViewModel.links.collectAsState()
    val options by exportViewModel.options.collectAsState()
    val generating by exportViewModel.generating.collectAsState()
    val context = LocalContext.current

    val allEntries = remember(seizures, healthNotes) {
        (seizures.map { Entry.SeizureEntry(it) } + healthNotes.map { Entry.NoteEntry(it) })
    }
    val previewCount = remember(allEntries, options) { filterExportEntries(allEntries, options).size }

    fun pickDate(current: Long?, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { current?.let { timeInMillis = it } }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                onPicked(cal.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export for vet") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (pets.size > 1) {
                Text("Pet", style = MaterialTheme.typography.labelLarge)
                val petOptions = listOf<String?>(null) + pets.map { it.id }
                SegmentedControl(
                    options = petOptions,
                    selected = options.petId,
                    onSelect = { id -> exportViewModel.updateOptions { it.copy(petId = id) } },
                    label = { id -> id?.let { petId -> pets.find { it.id == petId }?.name } ?: PET_ALL_LABEL },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Time range", style = MaterialTheme.typography.labelLarge)
            PillChipSelector(
                options = DateRangeOption.entries,
                selected = options.dateRange,
                onSelect = { range -> exportViewModel.updateOptions { it.copy(dateRange = range) } },
                label = { it.label }
            )
            if (options.dateRange == DateRangeOption.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        pickDate(options.customStartMillis) { millis ->
                            exportViewModel.updateOptions { it.copy(customStartMillis = millis) }
                        }
                    }) {
                        Text(options.customStartMillis?.let { DateTimeUtils.formatDate(it) } ?: "Start date")
                    }
                    OutlinedButton(onClick = {
                        pickDate(options.customEndMillis) { millis ->
                            exportViewModel.updateOptions { it.copy(customEndMillis = millis) }
                        }
                    }) {
                        Text(options.customEndMillis?.let { DateTimeUtils.formatDate(it) } ?: "End date")
                    }
                }
            }

            Text("Include", style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = options.includeSeizures,
                    onValueChange = { checked -> exportViewModel.updateOptions { it.copy(includeSeizures = checked) } }
                )
            ) {
                Checkbox(checked = options.includeSeizures, onCheckedChange = null)
                Text("Seizures")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = options.includeHealthNotes,
                    onValueChange = { checked -> exportViewModel.updateOptions { it.copy(includeHealthNotes = checked) } }
                )
            ) {
                Checkbox(checked = options.includeHealthNotes, onCheckedChange = null)
                Text("Health notes")
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = {}, enabled = false)
                    Text("Photos attached to notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Photo attachments aren't included in exports yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp)
                )
            }

            Text("Format", style = MaterialTheme.typography.labelLarge)
            SegmentedControl(
                options = listOf(ExportFormat.PDF, ExportFormat.CSV),
                selected = options.format,
                onSelect = { format -> exportViewModel.updateOptions { it.copy(format = format) } },
                label = { if (it == ExportFormat.PDF) "PDF" else "CSV" },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "$previewCount ${if (previewCount == 1) "entry" else "entries"} will be included.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            PrimaryPillButton(
                text = if (generating) "Generating…" else "Create export",
                enabled = !generating && previewCount > 0,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    exportViewModel.generate(
                        context = context,
                        pets = pets,
                        entries = allEntries,
                        vets = vets,
                        links = links
                    ) {
                        navController.navigate(Destinations.EXPORT_READY)
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
