package com.atnip.seizuretracker.ui.accessibility

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atnip.seizuretracker.data.local.UserPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Constructed once in [com.atnip.seizuretracker.MainActivity], independent of
 * [com.atnip.seizuretracker.ui.session.SessionViewModel] — accessibility is a device-level
 * concern orthogonal to auth/household state, so it applies to every screen including the
 * welcome/loading flow, not just the signed-in app.
 */
class AccessibilityViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = UserPrefs(appContext)

    val settings: StateFlow<A11ySettings> = combine(
        prefs.highContrast,
        prefs.largerText,
        prefs.reduceMotion
    ) { highContrast, largerText, reduceMotion ->
        A11ySettings(highContrast, largerText, reduceMotion)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), A11ySettings())

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { prefs.setHighContrast(enabled) }
    }

    fun setLargerText(enabled: Boolean) {
        viewModelScope.launch { prefs.setLargerText(enabled) }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { prefs.setReduceMotion(enabled) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AccessibilityViewModel(context.applicationContext) as T
            }
        }
    }
}
