package com.atnip.seizuretracker.ui.pet

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.local.UserPrefs
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.repository.PetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PetListViewModel(appContext: Context, private val householdId: String) : ViewModel() {

    private val prefs = UserPrefs(appContext)

    val pets: StateFlow<List<Pet>> = PetRepository.observePets(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The pet the dashboard is currently focused on. Falls back to the oldest pet (by
     * [Pet.createdAtMillis]) when no pet is stored yet, or the stored id no longer matches any
     * loaded pet (e.g. it was deleted on another device) — self-healing rather than requiring
     * explicit handling at every call site.
     */
    val activePet: StateFlow<Pet?> = combine(pets, prefs.activePetId) { list, storedId ->
        list.firstOrNull { it.id == storedId } ?: list.minByOrNull { it.createdAtMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setActivePet(petId: String) {
        viewModelScope.launch { prefs.setActivePetId(petId) }
    }

    fun addPet(pet: Pet, onDone: (petId: String) -> Unit = {}) {
        viewModelScope.launch {
            val petId = PetRepository.addPet(householdId, pet)
            onDone(petId)
        }
    }

    fun updatePet(pet: Pet, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            PetRepository.updatePet(householdId, pet)
            onDone()
        }
    }

    fun deletePet(petId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            PetRepository.deletePet(householdId, petId)
            onDone()
        }
    }

    companion object {
        fun factory(context: Context, householdId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PetListViewModel(context.applicationContext, householdId) as T
                }
            }
    }
}
