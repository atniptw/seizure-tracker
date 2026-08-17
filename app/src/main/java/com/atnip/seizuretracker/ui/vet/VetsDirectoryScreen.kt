package com.atnip.seizuretracker.ui.vet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.ui.common.RoleTag
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.PetListViewModel

/**
 * Screen 13 — one shared household-level vet directory: a clinic can cover several pets, and a
 * pet can have several vets. Role labels always read as text, never color alone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VetsDirectoryScreen(navController: NavController, vetViewModel: VetViewModel, petListViewModel: PetListViewModel) {
    val vets by vetViewModel.vets.collectAsState()
    val links by vetViewModel.links.collectAsState()
    val pets by petListViewModel.pets.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vets") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Destinations.ADD_VET) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add a vet") }
            )
        }
    ) { padding ->
        if (vets.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("No vets in the household directory yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                items(vets, key = { it.id }) { vet ->
                    VetRow(
                        vet = vet,
                        links = links.filter { it.vetId == vet.id },
                        pets = pets,
                        onClick = { navController.navigate(Destinations.vetDetail(vet.id)) }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VetRow(vet: Vet, links: List<PetVetLink>, pets: List<Pet>, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(Color(0xFF49454F), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MedicalServices, contentDescription = null, tint = Color.White)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(vet.name, style = MaterialTheme.typography.bodyLarge)
            val subtitle = vet.phone.ifBlank { vet.addressOrNotes }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (links.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    links.forEach { link ->
                        val petName = pets.find { it.id == link.petId }?.name ?: "Unknown pet"
                        RoleTag(petName = petName, role = link.role)
                    }
                }
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}
