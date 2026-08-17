package com.atnip.seizuretracker.ui.vet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.data.repository.PetVetLinkRepository
import com.atnip.seizuretracker.data.repository.VetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VetViewModel(private val householdId: String) : ViewModel() {

    val vets: StateFlow<List<Vet>> = VetRepository.observeVets(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val links: StateFlow<List<PetVetLink>> = PetVetLinkRepository.observeLinks(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addVet(vet: Vet, onDone: (vetId: String) -> Unit = {}) {
        viewModelScope.launch {
            val vetId = VetRepository.addVet(householdId, vet)
            onDone(vetId)
        }
    }

    fun updateVet(vet: Vet, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            VetRepository.updateVet(householdId, vet)
            onDone()
        }
    }

    /** Removes the vet from the directory along with every link to a pet — never touches entry history. */
    fun deleteVet(vetId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            VetRepository.deleteVet(householdId, vetId)
            onDone()
        }
    }

    fun addLink(petId: String, vetId: String, role: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            PetVetLinkRepository.addLink(householdId, petId, vetId, role)
            onDone()
        }
    }

    fun updateLinkRole(linkId: String, role: String) {
        viewModelScope.launch {
            PetVetLinkRepository.updateLinkRole(householdId, linkId, role)
        }
    }

    fun removeLink(linkId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            PetVetLinkRepository.removeLink(householdId, linkId)
            onDone()
        }
    }

    companion object {
        fun factory(householdId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VetViewModel(householdId) as T
            }
        }
    }
}
