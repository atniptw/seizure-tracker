package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A free-text health entry for a pet, logged alongside [Seizure]s via the same quick-add flow
 * but kept as its own collection (not merged into `seizures` with a type discriminator) so the
 * seizure form's shape and rules stay untouched.
 */
data class HealthNote(
    @DocumentId val id: String = "",
    val petId: String = "",
    val description: String = "",
    val timestampMillis: Long = 0L,
    val notes: String = "",
    val photoUri: String = "",
    val flaggedForVet: Boolean = true,
    val loggedByName: String = "",
    val loggedByUid: String = "",
    val createdAtMillis: Long = 0L
)
