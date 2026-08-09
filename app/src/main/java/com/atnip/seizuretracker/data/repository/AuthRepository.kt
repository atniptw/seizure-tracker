package com.atnip.seizuretracker.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

/**
 * Anonymous auth only. There's no login screen, no password to lose — every device gets a
 * stable-per-install uid the moment the app opens, and that uid is what Firestore security
 * rules check against a household's member list. Reinstalling the app (or clearing app data)
 * creates a new uid, so that device would need to re-join the household with the share code.
 */
object AuthRepository {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    suspend fun ensureSignedIn(): String {
        val existing = auth.currentUser
        if (existing != null) return existing.uid
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous sign-in failed to return a user")
    }
}
