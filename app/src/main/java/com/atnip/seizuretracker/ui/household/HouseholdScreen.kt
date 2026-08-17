package com.atnip.seizuretracker.ui.household

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.MemberProfile
import com.atnip.seizuretracker.ui.common.AvatarInitial
import com.atnip.seizuretracker.ui.common.ListRow
import com.atnip.seizuretracker.ui.common.OutlinePillButton
import com.atnip.seizuretracker.ui.theme.AlertRed

/** Screen 9. Household name renames on blur — there's no page-level Save button in the design. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(navController: NavController, householdViewModel: HouseholdViewModel, currentUid: String) {
    val household by householdViewModel.household.collectAsState()
    val members by householdViewModel.members.collectAsState()
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(household) {
        val h = household ?: return@LaunchedEffect
        if (!initialized) {
            name = h.name
            initialized = true
        }
    }
    var memberToRemove by remember { mutableStateOf<MemberProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Household") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Household code", style = MaterialTheme.typography.labelLarge)
                        Text(
                            household?.code ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Household code", household?.code ?: ""))
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code")
                    }
                }
            }

            OutlinePillButton(
                text = "Invite someone",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join our SeizureTracker household — use code ${household?.code.orEmpty()} in the app."
                        )
                    }
                    context.startActivity(Intent.createChooser(intent, "Invite someone"))
                }
            )

            HorizontalDivider()

            Text("Household name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && initialized && name.isNotBlank() && name != household?.name) {
                            householdViewModel.updateHouseholdName(name)
                        }
                    }
            )

            HorizontalDivider()

            Text("Members (${members.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            members.forEach { member ->
                val isSelf = member.uid == currentUid
                ListRow(
                    title = if (isSelf) "${member.displayName} · You" else member.displayName,
                    subtitle = authMethodLabel(member.authMethod),
                    leading = { AvatarInitial(name = member.displayName, isPrimary = isSelf) },
                    trailing = if (isSelf) null else {
                        {
                            IconButton(onClick = { memberToRemove = member }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove ${member.displayName}", tint = AlertRed)
                            }
                        }
                    }
                )
            }
        }
    }

    memberToRemove?.let { member ->
        RemoveMemberDialog(
            memberName = member.displayName,
            onConfirm = {
                householdViewModel.removeMember(member.uid)
                memberToRemove = null
            },
            onDismiss = { memberToRemove = null }
        )
    }
}

private fun authMethodLabel(authMethod: String): String = when (authMethod) {
    AuthMethods.GOOGLE -> "Signed in with Google"
    AuthMethods.ANONYMOUS -> "No Google account"
    else -> ""
}
