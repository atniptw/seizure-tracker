package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.PetVetLink
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object PetVetLinkRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun links(householdId: String) =
        db.collection("households").document(householdId).collection("petVetLinks")

    /** Live-updating list of every pet-vet link in this household. Filter client-side by petId/vetId. */
    fun observeLinks(householdId: String): Flow<List<PetVetLink>> = callbackFlow {
        val registration = links(householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(PetVetLink::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addLink(householdId: String, petId: String, vetId: String, role: String): String {
        val ref = links(householdId).document()
        val link = PetVetLink(
            id = ref.id,
            petId = petId,
            vetId = vetId,
            role = role,
            createdAtMillis = System.currentTimeMillis()
        )
        ref.set(link).await()
        return ref.id
    }

    suspend fun updateLinkRole(householdId: String, linkId: String, role: String) {
        links(householdId).document(linkId).update("role", role).await()
    }

    suspend fun removeLink(householdId: String, linkId: String) {
        links(householdId).document(linkId).delete().await()
    }
}
