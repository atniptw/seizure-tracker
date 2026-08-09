package com.atnip.seizuretracker.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.ui.session.SessionViewModel

private enum class WelcomeMode { SIGN_IN, CHOOSE, CREATE, JOIN }

@Composable
fun WelcomeScreen(session: SessionViewModel) {
    var mode by rememberSaveable {
        mutableStateOf(if (session.isSignedIn.value) WelcomeMode.CHOOSE else WelcomeMode.SIGN_IN)
    }
    val error by session.error.collectAsState()
    val suggestedName by session.suggestedName.collectAsState()
    val signedIn by session.isSignedIn.collectAsState()

    LaunchedEffect(signedIn) {
        if (signedIn && mode == WelcomeMode.SIGN_IN) mode = WelcomeMode.CHOOSE
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Seizure Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Log seizures, keep everyone in the household in sync, and export a clean report for the vet.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        when (mode) {
            WelcomeMode.SIGN_IN -> SignInStep(session, error)
            WelcomeMode.CHOOSE -> {
                Button(onClick = { mode = WelcomeMode.CREATE }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up a new dog")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { mode = WelcomeMode.JOIN }, modifier = Modifier.fillMaxWidth()) {
                    Text("Join with a household code")
                }
            }
            WelcomeMode.CREATE -> CreateForm(session, error, suggestedName) { mode = WelcomeMode.CHOOSE }
            WelcomeMode.JOIN -> JoinForm(session, error, suggestedName) { mode = WelcomeMode.CHOOSE }
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SignInStep(session: SessionViewModel, error: String?) {
    val context = LocalContext.current
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(error) { if (error != null) submitting = false }

    Button(
        onClick = {
            submitting = true
            session.signInWithGoogle(context)
        },
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text("Sign in with Google")
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { session.signInAnonymously() }) {
        Text("Continue without Google (e.g. a petsitter)")
    }
}

@Composable
private fun CreateForm(session: SessionViewModel, error: String?, suggestedName: String?, onBack: () -> Unit) {
    var dogName by rememberSaveable { mutableStateOf("") }
    var yourName by rememberSaveable { mutableStateOf(suggestedName.orEmpty()) }
    var submitting by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) submitting = false }

    OutlinedTextField(
        value = dogName,
        onValueChange = { dogName = it },
        label = { Text("Dog's name") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = yourName,
        onValueChange = { yourName = it },
        label = { Text("Your name") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            submitting = true
            session.createHousehold(dogName.trim(), yourName.trim())
        },
        enabled = dogName.isNotBlank() && yourName.isNotBlank() && !submitting,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors()
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text("Create")
        }
    }
    TextButton(onClick = onBack) { Text("Back") }
}

@Composable
private fun JoinForm(session: SessionViewModel, error: String?, suggestedName: String?, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var yourName by rememberSaveable { mutableStateOf(suggestedName.orEmpty()) }
    var submitting by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) submitting = false }

    Text("Ask whoever set up the app for their 6-character household code.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase() },
        label = { Text("Household code") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = yourName,
        onValueChange = { yourName = it },
        label = { Text("Your name") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            submitting = true
            session.joinHousehold(code.trim(), yourName.trim())
        },
        enabled = code.isNotBlank() && yourName.isNotBlank() && !submitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text("Join")
        }
    }
    TextButton(onClick = onBack) { Text("Back") }
}
