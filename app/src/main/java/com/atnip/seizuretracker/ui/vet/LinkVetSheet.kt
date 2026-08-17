package com.atnip.seizuretracker.ui.vet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.data.model.VetRoles
import com.atnip.seizuretracker.ui.common.AppBottomSheet
import com.atnip.seizuretracker.ui.common.PillChipSelector
import com.atnip.seizuretracker.ui.common.PrimaryPillButton

/** Screen 15 — launched from a pet's "Linked vets" section (`AddEditPetScreen`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkVetSheet(
    petName: String,
    vets: List<Vet>,
    linksForPet: List<PetVetLink>,
    onLinkExisting: (vetId: String, role: String) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedVetId by remember { mutableStateOf<String?>(null) }
    var selectedRole by remember { mutableStateOf(VetRoles.ALL.first()) }

    val alreadyLinkedVetIds = linksForPet.map { it.vetId }.toSet()
    val results = vets.filter { it.id !in alreadyLinkedVetIds && it.name.contains(query, ignoreCase = true) }

    AppBottomSheet(onDismissRequest = onDismiss) {
        Text("Link a vet for $petName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search vets in this household") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                modifier = Modifier.fillMaxWidth()
            )

            results.forEach { vet ->
                VetResultRow(vet = vet, isSelected = vet.id == selectedVetId, onClick = { selectedVetId = vet.id })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    .clickable { onAddNew(); onDismiss() }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add a new vet", modifier = Modifier.padding(start = 8.dp))
            }

            if (selectedVetId != null) {
                Text("Role for $petName", style = MaterialTheme.typography.labelLarge)
                PillChipSelector(
                    options = VetRoles.ALL,
                    selected = selectedRole,
                    onSelect = { selectedRole = it },
                    label = { it }
                )
            }

            PrimaryPillButton(
                text = "Link vet",
                enabled = selectedVetId != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selectedVetId?.let { vetId ->
                        onLinkExisting(vetId, selectedRole)
                        onDismiss()
                    }
                }
            )
        }
    }
}

@Composable
private fun VetResultRow(vet: Vet, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(Color(0xFF49454F), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MedicalServices, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(vet.name, modifier = Modifier.padding(start = 12.dp))
    }
}
