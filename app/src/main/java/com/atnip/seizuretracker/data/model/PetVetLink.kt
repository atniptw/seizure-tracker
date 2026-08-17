package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/** Roles a vet can hold for a specific pet, shown as a chip picker. */
object VetRoles {
    val ALL = listOf("General", "Emergency", "Neuro specialist", "Other")
}

/**
 * Joins one [Pet] to one [Vet] with a [role] scoped to that specific pair — the same clinic can
 * be "General" for one pet and "Emergency" for another, so role lives on the link, not on either
 * side. A flat auto-id doc (like [Seizure]) rather than an array embedded on the pet or vet, so
 * linking, re-roling, and unlinking are each a single-document write.
 */
data class PetVetLink(
    @DocumentId val id: String = "",
    val petId: String = "",
    val vetId: String = "",
    val role: String = VetRoles.ALL.first(),
    val createdAtMillis: Long = 0L
)
