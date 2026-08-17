package com.atnip.seizuretracker.ui.pet

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.Medication
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.PetSpecies
import com.atnip.seizuretracker.ui.common.LabeledTextField
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.ui.common.SegmentedControl
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.vet.LinkVetSheet
import com.atnip.seizuretracker.ui.vet.VetViewModel
import com.atnip.seizuretracker.util.DateTimeUtils
import java.util.Calendar

/**
 * Screen 5 — add or edit a pet via one screen keyed by [existingPetId], mirroring
 * `AddEditSeizureScreen`'s convention. The "Linked vets" section only applies once the pet
 * exists (editing), same as medications.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddEditPetScreen(
    navController: NavController,
    petListViewModel: PetListViewModel,
    vetViewModel: VetViewModel,
    existingPetId: String?
) {
    val pets by petListViewModel.pets.collectAsState()
    val existing = remember(existingPetId, pets) { existingPetId?.let { id -> pets.find { it.id == id } } }
    val isEdit = existingPetId != null
    val context = LocalContext.current
    val vets by vetViewModel.vets.collectAsState()
    val links by vetViewModel.links.collectAsState()
    var showLinkVetSheet by remember { mutableStateOf(false) }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var species by remember(existing) { mutableStateOf(existing?.species ?: PetSpecies.ALL.first()) }
    var breed by remember(existing) { mutableStateOf(existing?.breed ?: "") }
    var weight by remember(existing) { mutableStateOf(existing?.weightKg?.toString() ?: "") }
    var birthDateMillis by remember(existing) { mutableStateOf(existing?.birthDateMillis) }
    var medications by remember(existing) { mutableStateOf(existing?.medications ?: emptyList()) }
    var saving by remember { mutableStateOf(false) }

    fun pickBirthDate() {
        val cal = Calendar.getInstance().apply { birthDateMillis?.let { timeInMillis = it } }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                birthDateMillis = cal.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit pet" else "Add a pet") },
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

            Column {
                Text("Species", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                SegmentedControl(
                    options = PetSpecies.ALL,
                    selected = species,
                    onSelect = { species = it },
                    label = { it }
                )
            }

            LabeledTextField(label = "Breed", value = breed, onValueChange = { breed = it })

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    label = "Weight (kg)",
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.weight(1f)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Birth date", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { pickBirthDate() }, modifier = Modifier.fillMaxWidth()) {
                        Text(birthDateMillis?.let { DateTimeUtils.formatDate(it) } ?: "Set date")
                    }
                }
            }

            HorizontalDivider()

            Text("Medications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            medications.forEachIndexed { index, med ->
                MedicationEditor(
                    medication = med,
                    onChange = { updated -> medications = medications.toMutableList().also { it[index] = updated } },
                    onDelete = { medications = medications.toMutableList().also { it.removeAt(index) } }
                )
            }
            OutlinedButton(onClick = { medications = medications + Medication() }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add medication")
            }

            if (isEdit && existing != null) {
                HorizontalDivider()

                Text("Linked vets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val petLinks = links.filter { it.petId == existing.id }
                petLinks.forEach { link ->
                    val vet = vets.find { it.id == link.vetId }
                    Text(
                        "${vet?.name ?: "Unknown vet"} · ${link.role}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                OutlinedButton(onClick = { showLinkVetSheet = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Link a vet")
                }
            }

            Spacer(Modifier.height(8.dp))

            PrimaryPillButton(
                text = "Save",
                enabled = name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    saving = true
                    val pet = Pet(
                        id = existing?.id ?: "",
                        name = name.trim(),
                        species = species,
                        breed = breed.trim(),
                        weightKg = weight.toDoubleOrNull(),
                        birthDateMillis = birthDateMillis,
                        photoUri = existing?.photoUri ?: "",
                        medications = medications,
                        createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
                    )
                    if (isEdit) {
                        petListViewModel.updatePet(pet) { navController.popBackStack() }
                    } else {
                        petListViewModel.addPet(pet) { petId ->
                            petListViewModel.setActivePet(petId)
                            navController.popBackStack()
                        }
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLinkVetSheet && existing != null) {
        LinkVetSheet(
            petName = existing.name,
            vets = vets,
            linksForPet = links.filter { it.petId == existing.id },
            onLinkExisting = { vetId, role -> vetViewModel.addLink(existing.id, vetId, role) },
            onAddNew = { navController.navigate(Destinations.addVetForPet(existing.id)) },
            onDismiss = { showLinkVetSheet = false }
        )
    }
}

@Composable
private fun MedicationEditor(medication: Medication, onChange: (Medication) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Medication", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove medication")
                }
            }
            androidx.compose.material3.OutlinedTextField(
                value = medication.name,
                onValueChange = { onChange(medication.copy(name = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = medication.dose,
                    onValueChange = { onChange(medication.copy(dose = it)) },
                    label = { Text("Dose") },
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = medication.frequency,
                    onValueChange = { onChange(medication.copy(frequency = it)) },
                    label = { Text("Frequency") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = medication.notes,
                onValueChange = { onChange(medication.copy(notes = it)) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
