package com.atnip.seizuretracker.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.net.HttpURLConnection
import java.net.URL
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that points [FirebaseFirestore] and [FirebaseAuth] at the Firebase Local Emulator
 * Suite (see repo-root `firebase.json` — firestore on 8080, auth on 9099) instead of a real
 * Firebase project.
 *
 * Builds a [FirebaseOptions] by hand rather than depending on the real, gitignored
 * `app/google-services.json` — dummy apiKey/applicationId are fine, the emulator doesn't
 * validate them. This keeps the suite runnable with zero secrets, anywhere. Uses the same demo
 * project id as `firestore-tests/rules.test.js` (Phase 2) so both suites are consistent.
 *
 * Host is "localhost", not "10.0.2.2" — that alias only resolves to the host machine from a
 * real Android emulator/device; Robolectric runs directly on the JVM host, so "localhost" reaches
 * the emulator suite directly.
 *
 * Robolectric doesn't reset third-party static singletons (like Firebase's app registry)
 * between test methods within the same JVM, so each test explicitly tears down and rebuilds a
 * fresh [FirebaseApp] — reusing a [FirebaseFirestore] instance that already had `useEmulator`
 * called (and used) on it throws `IllegalStateException`. Emulator *data* is cleared after each
 * test via the REST clear-data endpoint so tests never see each other's writes.
 */
class FirebaseEmulatorRule : TestWatcher() {

    companion object {
        const val PROJECT_ID = "demo-seizuretracker-rules-test"
        private const val FIRESTORE_HOST = "localhost"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_HOST = "localhost"
        private const val AUTH_PORT = 9099
    }

    override fun starting(description: Description) {
        val context: Context = ApplicationProvider.getApplicationContext()

        // Start from a clean slate: any FirebaseApp left over from a previous test method in
        // this same JVM/classloader gets deleted so getInstance() below hands back brand-new
        // Firestore/Auth instances rather than ones already "started" against the emulator.
        FirebaseApp.getApps(context).forEach { it.delete() }

        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId("1:000000000000:android:0000000000000000000000")
            .setApiKey("fake-api-key-for-emulator-only")
            .build()
        FirebaseApp.initializeApp(context, options)

        FirebaseFirestore.getInstance().useEmulator(FIRESTORE_HOST, FIRESTORE_PORT)
        FirebaseAuth.getInstance().useEmulator(AUTH_HOST, AUTH_PORT)
        // Fresh FirebaseApp per test, but be explicit: no test should start "signed in".
        FirebaseAuth.getInstance().signOut()
    }

    override fun finished(description: Description) {
        clearFirestoreData()
    }

    private fun clearFirestoreData() {
        val url = URL(
            "http://$FIRESTORE_HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT_ID/databases/(default)/documents"
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.responseCode // force the request to actually execute
        } finally {
            connection.disconnect()
        }
    }
}
