package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.Seizure
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object SeizureRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun seizures(householdId: String) =
        db.collection("households").document(householdId).collection("seizures")

    /** Live-updating list of every seizure for this household, most recent first. */
    fun observeSeizures(householdId: String): Flow<List<Seizure>> = callbackFlow {
        val registration = seizures(householdId)
            .orderBy("timestampMillis", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(Seizure::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addSeizure(householdId: String, seizure: Seizure) {
        val ref = seizures(householdId).document()
        ref.set(seizure.copy(id = ref.id)).await()
    }

    suspend fun updateSeizure(householdId: String, seizure: Seizure) {
        require(seizure.id.isNotBlank()) { "Cannot update a seizure with no id" }
        seizures(householdId).document(seizure.id).set(seizure).await()
    }

    suspend fun deleteSeizure(householdId: String, seizureId: String) {
        seizures(householdId).document(seizureId).delete().await()
    }

    suspend fun getSeizureOnce(householdId: String, seizureId: String): Seizure? =
        seizures(householdId).document(seizureId).get().await().toObject(Seizure::class.java)
}
