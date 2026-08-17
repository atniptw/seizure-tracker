package com.atnip.seizuretracker.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.Entry
import com.atnip.seizuretracker.ui.common.EntryCard
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel

/**
 * "See all" from the dashboard — the merged seizure + health-note feed for the active pet.
 * Generalizes the old single-collection `SeizureHistoryScreen` now that entries come from two
 * source collections (see [Entry]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryHistoryScreen(
    navController: NavController,
    seizureListViewModel: SeizureListViewModel,
    healthNoteListViewModel: HealthNoteListViewModel,
    activePetId: String
) {
    val seizures by seizureListViewModel.seizures.collectAsState()
    val healthNotes by healthNoteListViewModel.healthNotes.collectAsState()
    val entries = remember(seizures, healthNotes, activePetId) {
        (seizures.filter { it.petId == activePetId }.map { Entry.SeizureEntry(it) } +
            healthNotes.filter { it.petId == activePetId }.map { Entry.NoteEntry(it) })
            .sortedByDescending { it.timestampMillis }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Entry history (${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No entries logged yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { entryKey(it) }) { entry ->
                    EntryCard(entry = entry, onClick = { navigateToEntry(navController, entry) })
                }
            }
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
