package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.util.HouseholdCode
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object HouseholdRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun households() = db.collection("households")

    // A separate, publicly-gettable (by any signed-in device) index mapping a short join code
    // to a household id. It deliberately holds nothing but that mapping — no dog name, no vet
    // info — so a device that only has the code (and hasn't joined yet) can resolve it to a
    // household id without being able to read anything else about that household. Firestore
    // security rules require a member for reads on /households/{id} itself, and a *query*
    // like whereEqualTo("code", ...) can't work under those rules for a non-member anyway
    // (list queries are evaluated per-document against the read rule), which is why this
    // exists as its own get()-by-id lookup instead.
    private fun codeIndex() = db.collection("codeIndex")

    /** Creates a brand-new household for a dog and returns its Firestore doc id. */
    suspend fun createHousehold(dogName: String, ownerUid: String): String {
        var code: String
        // Extremely unlikely to collide at 6 chars, but check anyway.
        do {
            code = HouseholdCode.generate()
        } while (codeIndex().document(code).get().await().exists())

        val ref = households().document()
        val household = Household(
            id = ref.id,
            code = code,
            dogName = dogName,
            members = listOf(ownerUid),
            createdAtMillis = System.currentTimeMillis()
        )

        val batch = db.batch()
        batch.set(ref, household)
        batch.set(codeIndex().document(code), mapOf("householdId" to ref.id))
        batch.commit().await()

        return ref.id
    }

    /** Resolves a human-entered join code to a household id. Null if no match. */
    suspend fun findHouseholdIdByCode(code: String): String? {
        val normalized = HouseholdCode.normalize(code)
        val snapshot = codeIndex().document(normalized).get().await()
        return snapshot.getString("householdId")
    }

    /** Adds [uid] to a household's member list so their device can read/write its data. */
    suspend fun joinHousehold(householdId: String, uid: String) {
        households().document(householdId)
            .update("members", FieldValue.arrayUnion(uid))
            .await()
    }

    /** Live updates to a single household's profile (dog info, meds, member list). */
    fun observeHousehold(householdId: String): Flow<Household?> = callbackFlow {
        val registration = households().document(householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(Household::class.java))
            }
        awaitClose { registration.remove() }
    }

    suspend fun updateDogProfile(
        householdId: String,
        dogName: String,
        dogBreed: String,
        dogDobMillis: Long?,
        dogWeightKg: Double?,
        diagnosisDateMillis: Long?,
        vetName: String,
        vetPhone: String,
        vetEmail: String
    ) {
        households().document(householdId).update(
            mapOf(
                "dogName" to dogName,
                "dogBreed" to dogBreed,
                "dogDobMillis" to dogDobMillis,
                "dogWeightKg" to dogWeightKg,
                "diagnosisDateMillis" to diagnosisDateMillis,
                "vetName" to vetName,
                "vetPhone" to vetPhone,
                "vetEmail" to vetEmail
            )
        ).await()
    }

    suspend fun updateMedications(householdId: String, medications: List<com.atnip.seizuretracker.data.model.Medication>) {
        households().document(householdId)
            .update("medications", medications.map { mapOf("name" to it.name, "dose" to it.dose, "frequency" to it.frequency, "notes" to it.notes) })
            .await()
    }
}
