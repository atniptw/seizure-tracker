package com.atnip.seizuretracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.AvatarInitial
import com.atnip.seizuretracker.ui.common.Entry
import com.atnip.seizuretracker.ui.common.EntryCard
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.ui.entry.QuickAddSheet
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.pet.PetSwitcherSheet
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.util.DateTimeUtils

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    seizureListViewModel: SeizureListViewModel,
    healthNoteListViewModel: HealthNoteListViewModel,
    petListViewModel: PetListViewModel
) {
    val pets by petListViewModel.pets.collectAsState()
    val activePet by petListViewModel.activePet.collectAsState()
    val allSeizures by seizureListViewModel.seizures.collectAsState()
    val allHealthNotes by healthNoteListViewModel.healthNotes.collectAsState()
    val entries = remember(allSeizures, allHealthNotes, activePet) {
        activePet?.let { pet ->
            (allSeizures.filter { it.petId == pet.id }.map { Entry.SeizureEntry(it) } +
                allHealthNotes.filter { it.petId == pet.id }.map { Entry.NoteEntry(it) })
                .sortedByDescending { it.timestampMillis }
        } ?: emptyList()
    }
    var showSwitcher by remember { mutableStateOf(false) }
    var showQuickAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activePet != null) {
                        Row(
                            modifier = Modifier.clickable { showSwitcher = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarInitial(name = activePet!!.name, isPrimary = true, size = 32.dp)
                            Text(activePet!!.name, modifier = Modifier.padding(start = 8.dp))
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch pet")
                        }
                    } else {
                        Text("Seizure Tracker")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Destinations.EXPORT) }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export for vet")
                    }
                    IconButton(onClick = { navController.navigate(Destinations.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (activePet != null) {
                ExtendedFloatingActionButton(
                    onClick = { showQuickAdd = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Log an entry") }
                )
            }
        }
    ) { padding ->
        if (activePet == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Pets, contentDescription = null)
                Spacer(Modifier.height(8.dp))
                Text("Add your first pet to start logging", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                PrimaryPillButton(text = "Add a pet", onClick = { navController.navigate(Destinations.ADD_PET) })
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SummaryCard(entries)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { navController.navigate(Destinations.ENTRY_HISTORY) }) {
                            Text("See all")
                        }
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("No entries logged yet.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                } else {
                    items(entries.take(5), key = { entryKey(it) }) { entry ->
                        EntryCard(entry = entry, onClick = { navigateToEntry(navController, entry) })
                    }
                }
            }
        }

        if (showSwitcher) {
            PetSwitcherSheet(
                pets = pets,
                activePetId = activePet?.id,
                onSelect = { pet -> petListViewModel.setActivePet(pet.id) },
                onAddPet = { navController.navigate(Destinations.ADD_PET) },
                onDismiss = { showSwitcher = false }
            )
        }

        if (showQuickAdd && activePet != null) {
            QuickAddSheet(
                petName = activePet!!.name,
                onSeizure = { navController.navigate(Destinations.ADD_SEIZURE) },
                onHealthNote = { navController.navigate(Destinations.addHealthNote(activePet!!.id)) },
                onDismiss = { showQuickAdd = false }
            )
        }
    }
}

private fun entryKey(entry: Entry): String = when (entry) {
    is Entry.SeizureEntry -> "seizure_${entry.seizure.id}"
    is Entry.NoteEntry -> "note_${entry.note.id}"
}

private fun navigateToEntry(navController: NavController, entry: Entry) {
    when (entry) {
        is Entry.SeizureEntry -> navController.navigate(Destinations.seizureDetail(entry.seizure.id))
        is Entry.NoteEntry -> navController.navigate(Destinations.editHealthNote(entry.note.id))
    }
}

@Composable
private fun SummaryCard(entries: List<Entry>) {
    val seizures = entries.filterIsInstance<Entry.SeizureEntry>()
    val lastSeizure = seizures.maxByOrNull { it.timestampMillis }
    val daysSince = lastSeizure?.let {
        ((System.currentTimeMillis() - it.timestampMillis) / (1000L * 60 * 60 * 24)).toInt()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(Icons.Filled.Pets, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            if (lastSeizure == null) {
                Text("No seizures recorded yet", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    when (daysSince) {
                        0 -> "Last seizure: today"
                        1 -> "Last seizure: yesterday"
                        else -> "Last seizure: $daysSince days ago"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(DateTimeUtils.formatDateTime(lastSeizure.timestampMillis), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("${seizures.size} seizure${if (seizures.size == 1) "" else "s"} logged", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
