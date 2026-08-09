package com.atnip.seizuretracker.ui.seizure

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.model.SeizureSymptoms
import com.atnip.seizuretracker.data.model.SeizureTypes
import com.atnip.seizuretracker.util.DateTimeUtils
import java.util.Calendar

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddEditSeizureScreen(
    navController: NavController,
    seizureListViewModel: SeizureListViewModel,
    displayName: String,
    uid: String,
    existingSeizureId: String?
) {
    val allSeizures by seizureListViewModel.seizures.collectAsState()
    val existing = remember(existingSeizureId, allSeizures) {
        existingSeizureId?.let { id -> allSeizures.find { it.id == id } }
    }
    val isEdit = existingSeizureId != null
    val context = LocalContext.current

    var timestampMillis by remember(existing) { mutableStateOf(existing?.timestampMillis ?: System.currentTimeMillis()) }
    var minutes by remember(existing) { mutableStateOf(((existing?.durationSeconds ?: 0L) / 60).toString().takeIf { existing != null } ?: "") }
    var seconds by remember(existing) { mutableStateOf(((existing?.durationSeconds ?: 0L) % 60).toString().takeIf { existing != null } ?: "") }
    var seizureType by remember(existing) { mutableStateOf(existing?.seizureType ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var symptoms by remember(existing) { mutableStateOf(existing?.symptoms?.toSet() ?: emptySet()) }
    var preSeizureSigns by remember(existing) { mutableStateOf(existing?.preSeizureSigns ?: "") }
    var possibleTriggers by remember(existing) { mutableStateOf(existing?.possibleTriggers ?: "") }
    var recoveryMinutes by remember(existing) { mutableStateOf(existing?.recoveryMinutes?.toString() ?: "") }
    var recoveryNotes by remember(existing) { mutableStateOf(existing?.recoveryNotes ?: "") }
    var rescueMedGiven by remember(existing) { mutableStateOf(existing?.rescueMedGiven ?: false) }
    var rescueMedDetails by remember(existing) { mutableStateOf(existing?.rescueMedDetails ?: "") }
    var notes by remember(existing) { mutableStateOf(existing?.notes ?: "") }
    var saving by remember { mutableStateOf(false) }

    fun pickDateTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        timestampMillis = cal.timeInMillis
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit seizure" else "Log a seizure") },
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
            Column {
                Text("When did it happen?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { pickDateTime() }) {
                    Text(DateTimeUtils.formatDateTime(timestampMillis))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("Duration (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter { c -> c.isDigit() } },
                    label = { Text("(sec)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
                OutlinedTextField(
                    value = seizureType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Seizure type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                androidx.compose.material3.ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                    SeizureTypes.ALL.forEach { type ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(type) },
                            onClick = { seizureType = type; typeMenuExpanded = false }
                        )
                    }
                }
            }

            Column {
                Text("Symptoms observed", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeizureSymptoms.ALL.forEach { symptom ->
                        val selected = symptoms.contains(symptom)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                symptoms = if (selected) symptoms - symptom else symptoms + symptom
                            },
                            label = { Text(symptom) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = preSeizureSigns,
                onValueChange = { preSeizureSigns = it },
                label = { Text("Signs before it started (aura, restlessness, etc.)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = possibleTriggers,
                onValueChange = { possibleTriggers = it },
                label = { Text("Possible triggers") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            OutlinedTextField(
                value = recoveryMinutes,
                onValueChange = { recoveryMinutes = it.filter { c -> c.isDigit() } },
                label = { Text("Recovery time (minutes)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = recoveryNotes,
                onValueChange = { recoveryNotes = it },
                label = { Text("Recovery behavior (confusion, pacing, hunger...)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Rescue medication given?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = rescueMedGiven, onCheckedChange = { rescueMedGiven = it })
            }
            if (rescueMedGiven) {
                OutlinedTextField(
                    value = rescueMedDetails,
                    onValueChange = { rescueMedDetails = it },
                    label = { Text("Which medication, dose, and when") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Additional notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    saving = true
                    val seizure = Seizure(
                        id = existing?.id ?: "",
                        timestampMillis = timestampMillis,
                        durationSeconds = (minutes.toLongOrNull() ?: 0L) * 60 + (seconds.toLongOrNull() ?: 0L),
                        seizureType = seizureType,
                        symptoms = symptoms.toList(),
                        preSeizureSigns = preSeizureSigns,
                        possibleTriggers = possibleTriggers,
                        recoveryMinutes = recoveryMinutes.toLongOrNull(),
                        recoveryNotes = recoveryNotes,
                        rescueMedGiven = rescueMedGiven,
                        rescueMedDetails = rescueMedDetails,
                        notes = notes,
                        loggedByName = existing?.loggedByName ?: displayName,
                        loggedByUid = existing?.loggedByUid ?: uid,
                        createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
                    )
                    if (isEdit) {
                        seizureListViewModel.updateSeizure(seizure) { navController.popBackStack() }
                    } else {
                        seizureListViewModel.addSeizure(seizure) { navController.popBackStack() }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isEdit) "Save changes" else "Save seizure")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
