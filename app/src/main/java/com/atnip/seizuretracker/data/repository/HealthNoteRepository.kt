package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.HealthNote
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object HealthNoteRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun healthNotes(householdId: String) =
        db.collection("households").document(householdId).collection("healthNotes")

    /** Live-updating list of every health note for this household, most recent first. */
    fun observeHealthNotes(householdId: String): Flow<List<HealthNote>> = callbackFlow {
        val registration = healthNotes(householdId)
            .orderBy("timestampMillis", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(HealthNote::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addHealthNote(householdId: String, note: HealthNote) {
        val ref = healthNotes(householdId).document()
        ref.set(note.copy(id = ref.id)).await()
    }

    suspend fun updateHealthNote(householdId: String, note: HealthNote) {
        require(note.id.isNotBlank()) { "Cannot update a health note with no id" }
        healthNotes(householdId).document(note.id).set(note).await()
    }

    suspend fun deleteHealthNote(householdId: String, noteId: String) {
        healthNotes(householdId).document(noteId).delete().await()
    }

    suspend fun getHealthNoteOnce(householdId: String, noteId: String): HealthNote? =
        healthNotes(householdId).document(noteId).get().await().toObject(HealthNote::class.java)
}
