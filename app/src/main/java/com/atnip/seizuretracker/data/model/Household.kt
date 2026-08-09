package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A household is the shared record for one dog. Everyone who knows the join [code] (or has
 * already joined) can read/write its data — that's how multiple people (you, your partner, a
 * petsitter) log seizures for the same dog from separate phones.
 *
 * [id] is populated automatically from the Firestore document id when read, and is
 * automatically excluded when written back (that's what @DocumentId does).
 */
data class Household(
    @DocumentId val id: String = "",
    val code: String = "",
    val dogName: String = "",
    val dogBreed: String = "",
    val dogDobMillis: Long? = null,
    val dogWeightKg: Double? = null,
    val diagnosisDateMillis: Long? = null,
    val vetName: String = "",
    val vetPhone: String = "",
    val vetEmail: String = "",
    val medications: List<Medication> = emptyList(),
    val members: List<String> = emptyList(),
    val createdAtMillis: Long = 0L
)
