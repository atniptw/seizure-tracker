package com.atnip.seizuretracker.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.R
import com.atnip.seizuretracker.data.local.UserPrefs
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data object NeedsSetup : SessionState
    data class Ready(val householdId: String, val uid: String, val displayName: String) : SessionState
}

class SessionViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = UserPrefs(appContext)

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSignedIn = MutableStateFlow(AuthRepository.currentUser != null)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _suggestedName = MutableStateFlow<String?>(null)
    val suggestedName: StateFlow<String?> = _suggestedName.asStateFlow()

    init {
        viewModelScope.launch { evaluateState() }
    }

    private suspend fun evaluateState() {
        val uid = AuthRepository.currentUser?.uid
        if (uid == null) {
            _state.value = SessionState.NeedsSetup
            return
        }
        val householdId = prefs.householdId.first()
        val displayName = prefs.displayName.first().orEmpty()
        _state.value = if (householdId != null && displayName.isNotBlank()) {
            SessionState.Ready(householdId, uid, displayName)
        } else {
            SessionState.NeedsSetup
        }
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            _error.value = null
            try {
                AuthRepository.signInAnonymously()
                _isSignedIn.value = true
                evaluateState()
            } catch (t: Throwable) {
                _error.value = "Couldn't sign in: ${t.message ?: "unknown error"}"
            }
        }
    }

    /** [context] must be an Activity context — pass `LocalContext.current` from the composable. */
    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _error.value = null
            try {
                val webClientId = context.getString(R.string.default_web_client_id)
                val user = AuthRepository.signInWithGoogle(context, webClientId)
                _suggestedName.value = user.displayName
                _isSignedIn.value = true
                evaluateState()
            } catch (t: Throwable) {
                _error.value = "Google sign-in failed: ${t.message ?: "unknown error"}"
            }
        }
    }

    fun createHousehold(dogName: String, yourName: String) {
        viewModelScope.launch {
            _error.value = null
            try {
                val uid = AuthRepository.currentUser?.uid ?: error("Not signed in")
                val householdId = HouseholdRepository.createHousehold(dogName, uid)
                prefs.setHouseholdId(householdId)
                prefs.setDisplayName(yourName)
                _state.value = SessionState.Ready(householdId, uid, yourName)
            } catch (t: Throwable) {
                _error.value = "Couldn't create household: ${t.message ?: "unknown error"}"
            }
        }
    }

    fun joinHousehold(code: String, yourName: String) {
        viewModelScope.launch {
            _error.value = null
            try {
                val uid = AuthRepository.currentUser?.uid ?: error("Not signed in")
                val householdId = HouseholdRepository.findHouseholdIdByCode(code)
                if (householdId == null) {
                    _error.value = "No household found for code \"$code\". Double-check it with whoever shared it."
                    return@launch
                }
                HouseholdRepository.joinHousehold(householdId, uid)
                prefs.setHouseholdId(householdId)
                prefs.setDisplayName(yourName)
                _state.value = SessionState.Ready(householdId, uid, yourName)
            } catch (t: Throwable) {
                _error.value = "Couldn't join household: ${t.message ?: "unknown error"}"
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SessionViewModel(context.applicationContext) as T
            }
        }
    }
}
