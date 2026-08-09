package com.atnip.seizuretracker.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    init {
        viewModelScope.launch {
            val uid = try {
                AuthRepository.ensureSignedIn()
            } catch (t: Throwable) {
                _error.value = "Couldn't connect: ${t.message ?: "unknown error"}"
                _state.value = SessionState.NeedsSetup
                return@launch
            }
            val householdId = prefs.householdId.first()
            val displayName = prefs.displayName.first().orEmpty()
            if (householdId != null && displayName.isNotBlank()) {
                _state.value = SessionState.Ready(householdId, uid, displayName)
            } else {
                _state.value = SessionState.NeedsSetup
            }
        }
    }

    fun createHousehold(dogName: String, yourName: String) {
        viewModelScope.launch {
            _error.value = null
            try {
                val uid = AuthRepository.ensureSignedIn()
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
                val uid = AuthRepository.ensureSignedIn()
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
