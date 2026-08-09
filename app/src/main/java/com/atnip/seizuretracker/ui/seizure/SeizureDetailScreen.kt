package com.atnip.seizuretracker.ui.seizure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.util.DateTimeUtils

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SeizureDetailScreen(
    navController: NavController,
    seizureListViewModel: SeizureListViewModel,
    seizureId: String
) {
    val seizures by seizureListViewModel.seizures.collectAsState()
    val seizure = seizures.find { it.id == seizureId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seizure detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (seizure != null) {
                        IconButton(onClick = { navController.navigate(Destinations.editSeizure(seizure.id)) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (seizure == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("This entry couldn't be found — it may have been deleted.")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(DateTimeUtils.formatDateTime(seizure.timestampMillis), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                DetailRow("Duration", DateTimeUtils.formatDuration(seizure.durationSeconds))
                DetailRow("Type", seizure.seizureType.ifBlank { "—" })
                DetailRow("Symptoms", seizure.symptoms.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—")
                DetailRow("Signs before onset", seizure.preSeizureSigns.ifBlank { "—" })
                DetailRow("Possible triggers", seizure.possibleTriggers.ifBlank { "—" })

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow("Recovery time", seizure.recoveryMinutes?.let { "$it min" } ?: "—")
                DetailRow("Recovery behavior", seizure.recoveryNotes.ifBlank { "—" })

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow("Rescue medication given", if (seizure.rescueMedGiven) "Yes" else "No")
                if (seizure.rescueMedGiven) {
                    DetailRow("Details", seizure.rescueMedDetails.ifBlank { "—" })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow("Notes", seizure.notes.ifBlank { "—" })
                DetailRow("Logged by", seizure.loggedByName.ifBlank { "—" })
            }
        }
    }

    if (showDeleteConfirm && seizure != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this entry?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    seizureListViewModel.deleteSeizure(seizure.id) { navController.popBackStack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
