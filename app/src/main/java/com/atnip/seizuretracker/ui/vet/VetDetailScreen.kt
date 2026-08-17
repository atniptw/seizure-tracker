package com.atnip.seizuretracker.ui.vet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.data.model.VetRoles
import com.atnip.seizuretracker.ui.common.AvatarInitial
import com.atnip.seizuretracker.ui.common.ConfirmDialog
import com.atnip.seizuretracker.ui.common.LabeledTextField
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.theme.AlertRed

/**
 * Screen 14 — add or edit a vet via one screen keyed by [existingVetId], mirroring
 * `AddEditPetScreen`'s convention. When [linkToPetId] is set (reached from a pet's "Add a new
 * vet" flow), saving a brand-new vet also links it to that pet with the default "General" role —
 * editable afterward from the "Linked pets" section here. Role sits on the pet-vet link, not on
 * the vet record: the same clinic can be "General" for one pet and "Emergency" for another.
 * Removing a vet only unlinks it from every pet; entries never reference vets, so history is
 * untouched.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VetDetailScreen(
    navController: NavController,
    vetViewModel: VetViewModel,
    petListViewModel: PetListViewModel,
    existingVetId: String?,
    linkToPetId: String? = null
) {
    val vets by vetViewModel.vets.collectAsState()
    val links by vetViewModel.links.collectAsState()
    val pets by petListViewModel.pets.collectAsState()
    val existing = remember(existingVetId, vets) { existingVetId?.let { id -> vets.find { it.id == id } } }
    val isEdit = existingVetId != null

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var phone by remember(existing) { mutableStateOf(existing?.phone ?: "") }
    var addressOrNotes by remember(existing) { mutableStateOf(existing?.addressOrNotes ?: "") }
    var saving by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showLinkPetDialog by remember { mutableStateOf(false) }

    val vetLinks = if (existingVetId != null) links.filter { it.vetId == existingVetId } else emptyList()
    val linkedPetIds = vetLinks.map { it.petId }.toSet()
    val unlinkedPets = pets.filter { it.id !in linkedPetIds }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) (existing?.name?.ifBlank { "Vet" } ?: "Vet") else "Add a vet") },
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
            LabeledTextField(label = "Name", value = name, onValueChange = { name = it })
            LabeledTextField(label = "Phone", value = phone, onValueChange = { phone = it })
            LabeledTextField(label = "Address / notes", value = addressOrNotes, onValueChange = { addressOrNotes = it })

            if (isEdit) {
                HorizontalDivider()

                Text("Linked pets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                vetLinks.forEach { link ->
                    val pet = pets.find { it.id == link.petId }
                    LinkedPetRow(
                        pet = pet,
                        role = link.role,
                        onRoleChange = { newRole -> vetViewModel.updateLinkRole(link.id, newRole) },
                        onUnlink = { vetViewModel.removeLink(link.id) }
                    )
                }
                OutlinedButton(onClick = { showLinkPetDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Link another pet")
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null, tint = AlertRed)
                    TextButton(onClick = { showRemoveConfirm = true }) {
                        Text("Remove this vet", color = AlertRed)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            PrimaryPillButton(
                text = "Save",
                enabled = name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    saving = true
                    val vet = Vet(
                        id = existing?.id ?: "",
                        name = name.trim(),
                        phone = phone.trim(),
                        addressOrNotes = addressOrNotes.trim(),
                        createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
                    )
                    if (isEdit) {
                        vetViewModel.updateVet(vet) { navController.popBackStack() }
                    } else {
                        vetViewModel.addVet(vet) { vetId ->
                            if (linkToPetId != null) {
                                vetViewModel.addLink(linkToPetId, vetId, VetRoles.ALL.first()) {
                                    navController.popBackStack()
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRemoveConfirm && existing != null) {
        ConfirmDialog(
            title = "Remove ${existing.name}?",
            body = "This unlinks it from every pet. Entries already logged aren't affected.",
            confirmLabel = "Remove",
            onConfirm = {
                showRemoveConfirm = false
                vetViewModel.deleteVet(existing.id) { navController.popBackStack() }
            },
            onDismiss = { showRemoveConfirm = false }
        )
    }

    if (showLinkPetDialog && existingVetId != null) {
        LinkPetDialog(
            pets = unlinkedPets,
            onLink = { petId, role ->
                showLinkPetDialog = false
                vetViewModel.addLink(petId, existingVetId, role)
            },
            onDismiss = { showLinkPetDialog = false }
        )
    }
}

@Composable
private fun LinkedPetRow(pet: Pet?, role: String, onRoleChange: (String) -> Unit, onUnlink: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        AvatarInitial(name = pet?.name ?: "?", isPrimary = false, size = 36.dp)
        Text(
            pet?.name ?: "Unknown pet",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp).weight(1f)
        )
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text("$role ▾")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                VetRoles.ALL.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onRoleChange(option); menuExpanded = false })
                }
            }
        }
        IconButton(onClick = onUnlink) {
            Icon(Icons.Filled.Close, contentDescription = "Unlink ${pet?.name ?: "pet"}")
        }
    }
}

@Composable
private fun LinkPetDialog(pets: List<Pet>, onLink: (petId: String, role: String) -> Unit, onDismiss: () -> Unit) {
    var selectedPetId by remember(pets) { mutableStateOf(pets.firstOrNull()?.id) }
    var selectedRole by remember { mutableStateOf(VetRoles.ALL.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link another pet") },
        text = {
            if (pets.isEmpty()) {
                Text("Every pet in the household is already linked to this vet.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pets.forEach { pet ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedPetId == pet.id,
                                onClick = { selectedPetId = pet.id }
                            )
                            Text(pet.name)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Role", style = MaterialTheme.typography.labelLarge)
                    com.atnip.seizuretracker.ui.common.PillChipSelector(
                        options = VetRoles.ALL,
                        selected = selectedRole,
                        onSelect = { selectedRole = it },
                        label = { it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedPetId?.let { onLink(it, selectedRole) } },
                enabled = selectedPetId != null
            ) { Text("Link") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
