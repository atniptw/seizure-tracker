package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.MemberProfile
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object MemberRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun members(householdId: String) =
        db.collection("households").document(householdId).collection("members")

    /** Live-updating list of every member's profile (display name, auth method) in this household. */
    fun observeMembers(householdId: String): Flow<List<MemberProfile>> = callbackFlow {
        val registration = members(householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { it.toObject(MemberProfile::class.java) } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    /** Doc id is always the caller's own uid — rules only allow a uid to write its own profile doc. */
    suspend fun upsertOwnProfile(householdId: String, uid: String, displayName: String, authMethod: String) {
        val profile = MemberProfile(
            uid = uid,
            displayName = displayName,
            authMethod = authMethod,
            joinedAtMillis = System.currentTimeMillis()
        )
        members(householdId).document(uid).set(profile).await()
    }

    suspend fun deleteMemberProfile(householdId: String, uid: String) {
        members(householdId).document(uid).delete().await()
    }
}
