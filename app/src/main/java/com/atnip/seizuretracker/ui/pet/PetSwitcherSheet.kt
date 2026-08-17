package com.atnip.seizuretracker.ui.pet

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.ui.common.AppBottomSheet
import com.atnip.seizuretracker.ui.common.AvatarInitial

/** Screen 4 — switching the active dashboard pet, distinct from editing any pet (see [ManagePetsScreen]). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PetSwitcherSheet(
    pets: List<Pet>,
    activePetId: String?,
    onSelect: (Pet) -> Unit,
    onAddPet: () -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss) {
        Text("Switch pet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pets.forEachIndexed { index, pet ->
                PetSwitcherRow(
                    pet = pet,
                    isSelected = pet.id == activePetId,
                    isPrimary = index == 0,
                    onClick = { onSelect(pet); onDismiss() }
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAddPet(); onDismiss() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
            Text("Add a pet", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun PetSwitcherRow(pet: Pet, isSelected: Boolean, isPrimary: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarInitial(name = pet.name, isPrimary = isPrimary)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(pet.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                pet.species,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
