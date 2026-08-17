package com.atnip.seizuretracker.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.ListRow
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.session.SessionViewModel
import com.atnip.seizuretracker.ui.theme.AlertRed

/** Screen 11. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(navController: NavController, petListViewModel: PetListViewModel, session: SessionViewModel) {
    val pets by petListViewModel.pets.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            ListRow(
                title = "Pets",
                subtitle = if (pets.isEmpty()) "Add your first pet" else "${pets.joinToString(", ") { it.name }} — edit any pet, add a new one",
                leading = { Icon(Icons.Filled.Pets, contentDescription = null) },
                onClick = { navController.navigate(Destinations.MANAGE_PETS) }
            )
            ListRow(
                title = "Vets",
                subtitle = "Contacts shared across the household",
                leading = { Icon(Icons.Filled.MedicalServices, contentDescription = null) },
                onClick = { navController.navigate(Destinations.VETS) }
            )
            HorizontalDivider()
            ListRow(
                title = "Household",
                subtitle = "Members, join code, rename",
                leading = { Icon(Icons.Filled.Person, contentDescription = null) },
                onClick = { navController.navigate(Destinations.HOUSEHOLD) }
            )
            ListRow(
                title = "Accessibility",
                subtitle = "High contrast, larger text, motion",
                leading = { Icon(Icons.Filled.Accessibility, contentDescription = null) },
                onClick = { navController.navigate(Destinations.ACCESSIBILITY) }
            )
            ListRow(
                title = "Export for vet",
                subtitle = "PDF or CSV report",
                leading = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                onClick = { navController.navigate(Destinations.EXPORT) }
            )
            HorizontalDivider()
            ListRow(
                title = "Sign out",
                titleColor = AlertRed,
                leading = { Icon(Icons.Filled.Logout, contentDescription = null, tint = AlertRed) },
                trailing = null,
                onClick = { session.signOut() }
            )
        }
    }
}
