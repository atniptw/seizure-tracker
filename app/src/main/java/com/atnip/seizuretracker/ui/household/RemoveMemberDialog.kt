package com.atnip.seizuretracker.ui.household

import androidx.compose.runtime.Composable
import com.atnip.seizuretracker.ui.common.ConfirmDialog

/** Screen 10 — built on the shared [ConfirmDialog] pattern. */
@Composable
fun RemoveMemberDialog(memberName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Remove $memberName from this household?",
        body = "$memberName will lose access on their device immediately. Entries they've already logged stay in the history.",
        confirmLabel = "Remove",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
