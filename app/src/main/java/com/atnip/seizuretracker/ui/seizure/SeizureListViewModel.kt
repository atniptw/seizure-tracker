package com.atnip.seizuretracker.ui.seizure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.repository.SeizureRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeizureListViewModel(private val householdId: String) : ViewModel() {

    val seizures: StateFlow<List<Seizure>> = SeizureRepository.observeSeizures(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addSeizure(seizure: Seizure, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            SeizureRepository.addSeizure(householdId, seizure)
            onDone()
        }
    }

    fun updateSeizure(seizure: Seizure, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            SeizureRepository.updateSeizure(householdId, seizure)
            onDone()
        }
    }

    fun deleteSeizure(seizureId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            SeizureRepository.deleteSeizure(householdId, seizureId)
            onDone()
        }
    }

    companion object {
        fun factory(householdId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeizureListViewModel(householdId) as T
            }
        }
    }
}
