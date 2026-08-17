package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.Pet
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object PetRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun pets(householdId: String) =
        db.collection("households").document(householdId).collection("pets")

    /** Live-updating list of every pet in this household, oldest first (creation order). */
    fun observePets(householdId: String): Flow<List<Pet>> = callbackFlow {
        val registration = pets(householdId)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(Pet::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addPet(householdId: String, pet: Pet): String {
        val ref = pets(householdId).document()
        ref.set(pet.copy(id = ref.id)).await()
        return ref.id
    }

    suspend fun updatePet(householdId: String, pet: Pet) {
        require(pet.id.isNotBlank()) { "Cannot update a pet with no id" }
        pets(householdId).document(pet.id).set(pet).await()
    }

    suspend fun deletePet(householdId: String, petId: String) {
        pets(householdId).document(petId).delete().await()
    }

    suspend fun getPetOnce(householdId: String, petId: String): Pet? =
        pets(householdId).document(petId).get().await().toObject(Pet::class.java)
}
