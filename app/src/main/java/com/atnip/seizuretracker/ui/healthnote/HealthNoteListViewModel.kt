package com.atnip.seizuretracker.ui.healthnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.repository.HealthNoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HealthNoteListViewModel(private val householdId: String) : ViewModel() {

    val healthNotes: StateFlow<List<HealthNote>> = HealthNoteRepository.observeHealthNotes(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addHealthNote(note: HealthNote, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            HealthNoteRepository.addHealthNote(householdId, note)
            onDone()
        }
    }

    fun updateHealthNote(note: HealthNote, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            HealthNoteRepository.updateHealthNote(householdId, note)
            onDone()
        }
    }

    fun deleteHealthNote(noteId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            HealthNoteRepository.deleteHealthNote(householdId, noteId)
            onDone()
        }
    }

    companion object {
        fun factory(householdId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HealthNoteListViewModel(householdId) as T
            }
        }
    }
}
