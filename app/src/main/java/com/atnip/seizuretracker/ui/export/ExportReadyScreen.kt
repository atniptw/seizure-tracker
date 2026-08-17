package com.atnip.seizuretracker.ui.export

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.common.OutlinePillButton
import com.atnip.seizuretracker.ui.common.PrimaryPillButton
import com.atnip.seizuretracker.ui.navigation.Destinations

/**
 * Screen 17 — reached after [ExportViewModel.generate] finishes. Share via the system sheet, or
 * copy the already-generated file to a user-chosen location via the Storage Access Framework.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportReadyScreen(navController: NavController, exportViewModel: ExportViewModel) {
    val result by exportViewModel.result.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Export ready") }) }
    ) { padding ->
        val r = result
        if (r == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Preparing your export…")
            }
        } else {
            ExportReadyContent(navController, exportViewModel, r, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun ExportReadyContent(
    navController: NavController,
    exportViewModel: ExportViewModel,
    result: ExportResult,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(result.mimeType)
    ) { uri -> if (uri != null) exportViewModel.saveToDevice(context, uri) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Export ready", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "${result.entryCount} ${if (result.entryCount == 1) "entry" else "entries"} for ${result.petNames}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(result.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(32.dp))

        PrimaryPillButton(
            text = "Share",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = result.mimeType
                    putExtra(Intent.EXTRA_STREAM, result.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share export"))
            }
        )
        Spacer(Modifier.height(12.dp))
        OutlinePillButton(
            text = "Save to device",
            modifier = Modifier.fillMaxWidth(),
            onClick = { saveLauncher.launch(result.fileName) }
        )
        Spacer(Modifier.height(12.dp))
        OutlinePillButton(
            text = "Done",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                exportViewModel.clearResult()
                navController.popBackStack(Destinations.DASHBOARD, false)
            }
        )
    }
}
