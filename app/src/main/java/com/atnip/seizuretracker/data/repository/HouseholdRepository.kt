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

    /**
     * Creates a brand-new household and returns its Firestore doc id. Also writes the creator's
     * own [MemberProfile] doc — a separate write *after* the household+codeIndex batch commits,
     * since by then the creator is already a committed member and no rule-evaluation ordering
     * questions arise. No pet is created here — a fresh household lands on the dashboard's
     * empty state, and the first pet is added from there (see `ui/pet/AddEditPetScreen.kt`).
     */
    suspend fun createHousehold(householdName: String, ownerUid: String, displayName: String, authMethod: String): String {
        var code: String
        // Extremely unlikely to collide at 6 chars, but check anyway.
        do {
            code = HouseholdCode.generate()
        } while (codeIndex().document(code).get().await().exists())

        val ref = households().document()
        val household = Household(
            id = ref.id,
            code = code,
            name = householdName,
            members = listOf(ownerUid),
            createdAtMillis = System.currentTimeMillis()
        )

        val batch = db.batch()
        batch.set(ref, household)
        batch.set(codeIndex().document(code), mapOf("householdId" to ref.id))
        batch.commit().await()

        MemberRepository.upsertOwnProfile(ref.id, ownerUid, displayName, authMethod)

        return ref.id
    }

    /** Resolves a human-entered join code to a household id. Null if no match. */
    suspend fun findHouseholdIdByCode(code: String): String? {
        val normalized = HouseholdCode.normalize(code)
        val snapshot = codeIndex().document(normalized).get().await()
        return snapshot.getString("householdId")
    }

    /**
     * Adds [uid] to a household's member list so their device can read/write its data, then
     * writes their own [MemberProfile] doc as a second, non-batched write (the `members` rule
     * for the profile doc itself requires the caller already be a household member, so the
     * membership write has to land first).
     */
    suspend fun joinHousehold(householdId: String, uid: String, displayName: String, authMethod: String) {
        households().document(householdId)
            .update("members", FieldValue.arrayUnion(uid))
            .await()
        MemberRepository.upsertOwnProfile(householdId, uid, displayName, authMethod)
    }

    /** Renames the household (e.g. "The Bear & Milo house"). */
    suspend fun updateHouseholdName(householdId: String, name: String) {
        households().document(householdId).update("name", name).await()
    }

    /**
     * Removes [uid] from the household's member list and deletes their profile doc. Deletes the
     * profile *before* the array removal: the profile-doc delete rule requires the caller still
     * be a household member at the time of that write, which would fail on a self-removal if
     * the array removal (which drops the caller from `members`) had already landed first.
     */
    suspend fun removeMember(householdId: String, uid: String) {
        MemberRepository.deleteMemberProfile(householdId, uid)
        households().document(householdId)
            .update("members", FieldValue.arrayRemove(uid))
            .await()
    }

    /** Live updates to a single household's profile (name, code, member list). */
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
}
