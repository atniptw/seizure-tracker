package com.atnip.seizuretracker.ui.healthnote

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.util.DateTimeUtils
import java.util.Calendar

/**
 * Screen 3 — deliberately minimal (free text, when, notes, photo). Resist adding structured
 * fields without user research, per the design handoff's stated product priority.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddEditHealthNoteScreen(
    navController: NavController,
    healthNoteListViewModel: HealthNoteListViewModel,
    displayName: String,
    uid: String,
    activePetId: String,
    existingNoteId: String?
) {
    val allNotes by healthNoteListViewModel.healthNotes.collectAsState()
    val existing = remember(existingNoteId, allNotes) {
        existingNoteId?.let { id -> allNotes.find { it.id == id } }
    }
    val isEdit = existingNoteId != null
    val context = LocalContext.current

    var description by remember(existing) { mutableStateOf(existing?.description ?: "") }
    var timestampMillis by remember(existing) { mutableStateOf(existing?.timestampMillis ?: System.currentTimeMillis()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes ?: "") }
    var photoUri by remember(existing) { mutableStateOf(existing?.photoUri ?: "") }
    var saving by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) photoUri = uri.toString() }

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
                title = { Text("Health note") },
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
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("What's going on?") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Column {
                Text("When did it start?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { pickDateTime() }) {
                    Text(DateTimeUtils.formatDateTime(timestampMillis))
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(if (photoUri.isBlank()) "Add a photo" else "Change photo")
            }

            Spacer(Modifier.height(8.dp))

            PrimaryPillButton(
                text = "Save note",
                enabled = description.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    saving = true
                    val note = HealthNote(
                        id = existing?.id ?: "",
                        petId = existing?.petId ?: activePetId,
                        description = description,
                        timestampMillis = timestampMillis,
                        notes = notes,
                        photoUri = photoUri,
                        loggedByName = existing?.loggedByName ?: displayName,
                        loggedByUid = existing?.loggedByUid ?: uid,
                        createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
                    )
                    if (isEdit) {
                        healthNoteListViewModel.updateHealthNote(note) { navController.popBackStack() }
                    } else {
                        healthNoteListViewModel.addHealthNote(note) { navController.popBackStack() }
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
