package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.Vet
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object VetRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun vets(householdId: String) =
        db.collection("households").document(householdId).collection("vets")

    private fun links(householdId: String) =
        db.collection("households").document(householdId).collection("petVetLinks")

    /** Live-updating list of every vet in this household's directory, oldest first. */
    fun observeVets(householdId: String): Flow<List<Vet>> = callbackFlow {
        val registration = vets(householdId)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(Vet::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addVet(householdId: String, vet: Vet): String {
        val ref = vets(householdId).document()
        ref.set(vet.copy(id = ref.id)).await()
        return ref.id
    }

    suspend fun updateVet(householdId: String, vet: Vet) {
        require(vet.id.isNotBlank()) { "Cannot update a vet with no id" }
        vets(householdId).document(vet.id).set(vet).await()
    }

    /**
     * Removes a vet from the directory along with every link to a pet. Never touches
     * `seizures`/`healthNotes` — entries don't reference vets, so this can't delete visit
     * history, only the directory entry and its current pet links.
     */
    suspend fun deleteVet(householdId: String, vetId: String) {
        val linkedDocs = links(householdId).whereEqualTo("vetId", vetId).get().await()
        val batch = db.batch()
        linkedDocs.documents.forEach { batch.delete(it.reference) }
        batch.delete(vets(householdId).document(vetId))
        batch.commit().await()
    }
}
