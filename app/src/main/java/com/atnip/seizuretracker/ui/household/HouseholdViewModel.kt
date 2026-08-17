package com.atnip.seizuretracker.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.data.model.MemberProfile
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.MemberRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HouseholdViewModel(private val householdId: String) : ViewModel() {

    val household: StateFlow<Household?> = HouseholdRepository.observeHousehold(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val members: StateFlow<List<MemberProfile>> = MemberRepository.observeMembers(householdId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateHouseholdName(name: String) {
        viewModelScope.launch {
            HouseholdRepository.updateHouseholdName(householdId, name)
        }
    }

    fun removeMember(uid: String) {
        viewModelScope.launch {
            HouseholdRepository.removeMember(householdId, uid)
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
