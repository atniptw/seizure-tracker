package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/** Species options for a pet, shown as a 3-way picker. */
object PetSpecies {
    val ALL = listOf("Dog", "Cat", "Other")
}

/**
 * One pet in a household. A household can have any number of pets; each pet owns its own
 * profile and maintenance [medications]. Linked vets live separately in [PetVetLink] (a pet can
 * have several vets, a vet can serve several pets).
 *
 * [id] is populated automatically from the Firestore document id when read, and is
 * automatically excluded when written back (that's what @DocumentId does).
 */
data class Pet(
    @DocumentId val id: String = "",
    val name: String = "",
    val species: String = PetSpecies.ALL.first(),
    val breed: String = "",
    val weightKg: Double? = null,
    val birthDateMillis: Long? = null,
    val photoUri: String = "",
    val medications: List<Medication> = emptyList(),
    val createdAtMillis: Long = 0L
)
