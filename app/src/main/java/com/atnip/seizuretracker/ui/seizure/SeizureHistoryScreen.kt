package com.atnip.seizuretracker.ui.seizure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.util.DateTimeUtils

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SeizureHistoryScreen(navController: NavController, seizureListViewModel: SeizureListViewModel) {
    val seizures by seizureListViewModel.seizures.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seizure history (${seizures.size})") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (seizures.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No seizures logged yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(seizures, key = { it.id }) { seizure ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Destinations.seizureDetail(seizure.id)) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                DateTimeUtils.formatDateTime(seizure.timestampMillis),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                seizure.seizureType.ifBlank { "Type not recorded" } + " · " + DateTimeUtils.formatDuration(seizure.durationSeconds),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (seizure.loggedByName.isNotBlank()) {
                                Text(
                                    "Logged by ${seizure.loggedByName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
