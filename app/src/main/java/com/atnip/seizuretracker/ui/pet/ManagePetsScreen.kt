package com.atnip.seizuretracker.ui.pet

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.AvatarInitial
import com.atnip.seizuretracker.ui.common.ListRow
import com.atnip.seizuretracker.ui.navigation.Destinations

/** Screen 12 — tapping a pet opens its edit screen directly; it never changes the active dashboard pet (that's [PetSwitcherSheet]'s job). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ManagePetsScreen(navController: NavController, petListViewModel: PetListViewModel) {
    val pets by petListViewModel.pets.collectAsState()
    val activePet by petListViewModel.activePet.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pets") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            items(pets, key = { it.id }) { pet ->
                val isCurrent = pet.id == activePet?.id
                ListRow(
                    title = if (isCurrent) "${pet.name} · Current" else pet.name,
                    subtitle = "${pet.species} · breed, weight, vets, meds",
                    leading = { AvatarInitial(name = pet.name, isPrimary = isCurrent) },
                    onClick = { navController.navigate(Destinations.editPet(pet.id)) }
                )
                HorizontalDivider()
            }
            item {
                ListRow(
                    title = "Add a pet",
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    },
                    trailing = null,
                    onClick = { navController.navigate(Destinations.ADD_PET) }
                )
            }
        }
    }
}
