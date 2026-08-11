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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.ui.household.HouseholdViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.util.DateTimeUtils

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    householdViewModel: HouseholdViewModel,
    seizureListViewModel: SeizureListViewModel
) {
    val household by householdViewModel.household.collectAsState()
    val seizures by seizureListViewModel.seizures.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(household?.dogName?.ifBlank { "Seizure Tracker" } ?: "Seizure Tracker") },
                actions = {
                    IconButton(onClick = { navController.navigate(Destinations.EXPORT) }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export for vet")
                    }
                    IconButton(onClick = { navController.navigate(Destinations.DOG_PROFILE) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Dog profile & settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Destinations.ADD_SEIZURE) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Log a seizure") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SummaryCard(seizures)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent seizures", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { navController.navigate(Destinations.HISTORY) }) {
                        Text("See all")
                    }
                }
            }

            if (seizures.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("No seizures logged yet.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                items(seizures.take(5)) { seizure ->
                    RecentSeizureRow(seizure) {
                        navController.navigate(Destinations.seizureDetail(seizure.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(seizures: List<Seizure>) {
    val last = seizures.firstOrNull()
    val daysSince = last?.let {
        ((System.currentTimeMillis() - it.timestampMillis) / (1000L * 60 * 60 * 24)).toInt()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(Icons.Filled.Pets, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            if (last == null) {
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
                Text(DateTimeUtils.formatDateTime(last.timestampMillis), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text("${seizures.size} total logged", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecentSeizureRow(seizure: Seizure, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(DateTimeUtils.formatDateTime(seizure.timestampMillis), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                seizure.seizureType.ifBlank { "Type not recorded" } + " · " + DateTimeUtils.formatDuration(seizure.durationSeconds),
                style = MaterialTheme.typography.bodyMedium
            )
            if (seizure.loggedByName.isNotBlank()) {
                Text("Logged by ${seizure.loggedByName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
