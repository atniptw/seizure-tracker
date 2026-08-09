package com.atnip.seizuretracker.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.data.model.Medication
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HouseholdViewModel(private val householdId: String) : ViewModel() {

    val household: StateFlow<Household?> = HouseholdRepository.observeHousehold(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateDogProfile(
        dogName: String,
        dogBreed: String,
        dogDobMillis: Long?,
        dogWeightKg: Double?,
        diagnosisDateMillis: Long?,
        vetName: String,
        vetPhone: String,
        vetEmail: String
    ) {
        viewModelScope.launch {
            HouseholdRepository.updateDogProfile(
                householdId, dogName, dogBreed, dogDobMillis, dogWeightKg,
                diagnosisDateMillis, vetName, vetPhone, vetEmail
            )
        }
    }

    fun updateMedications(medications: List<Medication>) {
        viewModelScope.launch {
            HouseholdRepository.updateMedications(householdId, medications)
        }
    }

    companion object {
        fun factory(householdId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HouseholdViewModel(householdId) as T
            }
        }
    }
}
