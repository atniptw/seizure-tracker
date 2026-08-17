package com.atnip.seizuretracker.data.repository

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Two sign-in paths, both ending in a Firestore-rules-checked uid:
 * - Google (Credential Manager): identity is tied to the person's Google account, so it
 *   survives the phone being wiped/reset — signing in again returns the same uid.
 * - Anonymous: a stable-per-install uid with no account behind it, for anyone who'd rather not
 *   attach Google (e.g. a petsitter). Lost if the app is reinstalled or its data cleared.
 */
object AuthRepository {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    suspend fun signInAnonymously(): String {
        val existing = auth.currentUser
        if (existing != null) return existing.uid
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous sign-in failed to return a user")
    }

    /** For an anonymous uid, this is unrecoverable — the next sign-in mints a brand-new uid. */
    fun signOut() {
        auth.signOut()
    }

    /** [context] must be an Activity context — Credential Manager needs it to show the account picker UI. */
    suspend fun signInWithGoogle(context: Context, webClientId: String): FirebaseUser {
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(context).getCredential(context, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
        val result = auth.signInWithCredential(firebaseCredential).await()
        return result.user ?: error("Google sign-in failed to return a user")
    }
}
