package com.atnip.seizuretracker.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "seizure_tracker_prefs")

/**
 * Small local-only settings: which household this device belongs to, what name to attach to
 * entries this device logs ("logged by Tom" vs a raw user id), and this device's accessibility
 * preferences. Deliberately NOT synced — each device sets its own display name and a11y prefs.
 */
class UserPrefs(private val context: Context) {

    private object Keys {
        val HOUSEHOLD_ID = stringPreferencesKey("household_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val LARGER_TEXT = booleanPreferencesKey("larger_text")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val ACTIVE_PET_ID = stringPreferencesKey("active_pet_id")
    }

    val householdId: Flow<String?> = context.dataStore.data.map { it[Keys.HOUSEHOLD_ID] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }

    // Which pet the dashboard is currently focused on. Deliberately per-device, not synced to
    // the household doc — syncing it would mean one member switching their dashboard's focus pet
    // silently flips it on every other member's phone too.
    val activePetId: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_PET_ID] }

    // Larger text defaults on; high-contrast and reduce-motion default off — matches the design
    // handoff's stated defaults for the accessibility settings screen.
    val highContrast: Flow<Boolean> = context.dataStore.data.map { it[Keys.HIGH_CONTRAST] ?: false }
    val largerText: Flow<Boolean> = context.dataStore.data.map { it[Keys.LARGER_TEXT] ?: true }
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { it[Keys.REDUCE_MOTION] ?: false }

    suspend fun setHouseholdId(id: String) {
        context.dataStore.edit { it[Keys.HOUSEHOLD_ID] = id }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HIGH_CONTRAST] = enabled }
    }

    suspend fun setLargerText(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LARGER_TEXT] = enabled }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REDUCE_MOTION] = enabled }
    }

    suspend fun setActivePetId(petId: String) {
        context.dataStore.edit { it[Keys.ACTIVE_PET_ID] = petId }
    }

    suspend fun clearHousehold() {
        context.dataStore.edit {
            it.remove(Keys.HOUSEHOLD_ID)
            it.remove(Keys.ACTIVE_PET_ID)
        }
    }
}
