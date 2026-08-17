package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/** How a household member is signed in — shown as a subtitle on the household member list. */
object AuthMethods {
    const val GOOGLE = "google"
    const val ANONYMOUS = "anonymous"
}

/**
 * Metadata about one household member — display name and sign-in method — synced so every
 * device can show them, not just the device they were set on. [uid] (the Firestore doc id) is
 * only ever equal to the profile's own auth uid; this doc is never consulted for access control
 * — [Household.members] remains the sole source of truth for who can read/write a household.
 */
data class MemberProfile(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val authMethod: String = "",
    val joinedAtMillis: Long = 0L
)
