package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A household is the shared record for a group of pets. Everyone who knows the join [code] (or
 * has already joined) can read/write its data — that's how multiple people (you, your partner, a
 * petsitter) log entries for the same pets from separate phones. Per-pet profile data (name,
 * species, breed, medications, etc.) lives in the household's `pets` subcollection — see [Pet] —
 * not on this doc.
 *
 * [id] is populated automatically from the Firestore document id when read, and is
 * automatically excluded when written back (that's what @DocumentId does).
 */
data class Household(
    @DocumentId val id: String = "",
    val code: String = "",
    val name: String = "",
    val members: List<String> = emptyList(),
    val createdAtMillis: Long = 0L
)
