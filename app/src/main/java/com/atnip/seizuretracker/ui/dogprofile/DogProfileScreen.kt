package com.atnip.seizuretracker.ui.dogprofile

import android.content.ClipData
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.atnip.seizuretracker.ui.household.HouseholdViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DogProfileScreen(navController: NavController, householdViewModel: HouseholdViewModel) {
    val household by householdViewModel.household.collectAsState()

    var dogName by remember { mutableStateOf("") }
    var dogBreed by remember { mutableStateOf("") }
    var dogWeight by remember { mutableStateOf("") }
    var vetName by remember { mutableStateOf("") }
    var vetPhone by remember { mutableStateOf("") }
    var vetEmail by remember { mutableStateOf("") }
    var medications by remember { mutableStateOf(listOf<Medication>()) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(household) {
        val h = household ?: return@LaunchedEffect
        if (!initialized) {
            dogName = h.dogName
            dogBreed = h.dogBreed
            dogWeight = h.dogWeightKg?.toString() ?: ""
            vetName = h.vetName
            vetPhone = h.vetPhone
            vetEmail = h.vetEmail
            medications = h.medications
            initialized = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dog profile & settings") },
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
            household?.code?.let { code -> HouseholdCodeRow(code) }

            Text("Dog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = dogName, onValueChange = { dogName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = dogBreed, onValueChange = { dogBreed = it }, label = { Text("Breed") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = dogWeight, onValueChange = { dogWeight = it }, label = { Text("Weight (kg)") }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()

            Text("Vet contact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = vetName, onValueChange = { vetName = it }, label = { Text("Vet / clinic name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vetPhone, onValueChange = { vetPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vetEmail, onValueChange = { vetEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()

            Text("Current medications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.Button(
                onClick = {
                    householdViewModel.updateDogProfile(
                        dogName = dogName,
                        dogBreed = dogBreed,
                        dogDobMillis = household?.dogDobMillis,
                        dogWeightKg = dogWeight.toDoubleOrNull(),
                        diagnosisDateMillis = household?.diagnosisDateMillis,
                        vetName = vetName,
                        vetPhone = vetPhone,
                        vetEmail = vetEmail
                    )
                    householdViewModel.updateMedications(medications)
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HouseholdCodeRow(code: String) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Household code", style = MaterialTheme.typography.labelLarge)
                Text(code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Household code", code))
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code")
            }
        }
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
            OutlinedTextField(
                value = medication.name,
                onValueChange = { onChange(medication.copy(name = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = medication.dose,
                    onValueChange = { onChange(medication.copy(dose = it)) },
                    label = { Text("Dose") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = medication.frequency,
                    onValueChange = { onChange(medication.copy(frequency = it)) },
                    label = { Text("Frequency") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = medication.notes,
                onValueChange = { onChange(medication.copy(notes = it)) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
