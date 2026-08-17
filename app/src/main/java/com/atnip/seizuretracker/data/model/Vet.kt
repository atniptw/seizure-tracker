package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A vet or clinic in the household's shared directory. One directory per household — a clinic
 * can serve multiple pets, and a pet can have multiple vets — linked via [PetVetLink].
 */
data class Vet(
    @DocumentId val id: String = "",
    val name: String = "",
    val phone: String = "",
    val addressOrNotes: String = "",
    val createdAtMillis: Long = 0L
)
