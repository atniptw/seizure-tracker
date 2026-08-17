package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.util.DateTimeUtils

/** The dashboard/entry-history row for one [Entry] — a seizure or a health note. */
@Composable
fun EntryCard(entry: Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryTypeTag(if (entry is Entry.SeizureEntry) EntryType.SEIZURE else EntryType.NOTE)
                Spacer(Modifier.width(8.dp))
                Text(
                    DateTimeUtils.formatDateTime(entry.timestampMillis),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(entryDetail(entry), style = MaterialTheme.typography.bodyLarge)
            val loggedByName = entryLoggedByName(entry)
            if (loggedByName.isNotBlank()) {
                Text(
                    "Logged by $loggedByName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun entryDetail(entry: Entry): String = when (entry) {
    is Entry.SeizureEntry -> {
        val s = entry.seizure
        s.seizureType.ifBlank { "Type not recorded" } + " · " + DateTimeUtils.formatDuration(s.durationSeconds)
    }
    is Entry.NoteEntry -> entry.note.description.ifBlank { "Health note" }
}

private fun entryLoggedByName(entry: Entry): String = when (entry) {
    is Entry.SeizureEntry -> entry.seizure.loggedByName
    is Entry.NoteEntry -> entry.note.loggedByName
}
