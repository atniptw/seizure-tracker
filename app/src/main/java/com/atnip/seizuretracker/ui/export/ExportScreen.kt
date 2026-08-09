package com.atnip.seizuretracker.ui.export

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.atnip.seizuretracker.ui.household.HouseholdViewModel
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.util.CsvExporter
import com.atnip.seizuretracker.util.PdfExporter

private val RANGE_LABELS = listOf("Last 30 days", "Last 90 days", "All time")
private val RANGE_DAYS = listOf(30, 90, null)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    navController: NavController,
    householdViewModel: HouseholdViewModel,
    seizureListViewModel: SeizureListViewModel
) {
    val household by householdViewModel.household.collectAsState()
    val allSeizures by seizureListViewModel.seizures.collectAsState()
    val context = LocalContext.current
    var selectedRange by remember { mutableIntStateOf(2) } // default "All time"

    val cutoffMillis = RANGE_DAYS[selectedRange]?.let { days ->
        System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
    }
    val filtered = if (cutoffMillis == null) allSeizures else allSeizures.filter { it.timestampMillis >= cutoffMillis }

    fun shareFile(uri: android.net.Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share seizure log"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export for vet") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Time range", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RANGE_LABELS.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedRange == index,
                        onClick = { selectedRange = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RANGE_LABELS.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Text(
                "${filtered.size} seizure${if (filtered.size == 1) "" else "s"} will be included.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val uri = PdfExporter.export(context, household, filtered)
                    shareFile(uri, "application/pdf")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share as PDF")
            }

            OutlinedButton(
                onClick = {
                    val uri = CsvExporter.export(context, household?.dogName ?: "dog", filtered)
                    shareFile(uri, "text/csv")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share as CSV (spreadsheet)")
            }
        }
    }
}
