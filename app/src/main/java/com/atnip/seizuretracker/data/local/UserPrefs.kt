package com.atnip.seizuretracker.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "seizure_tracker_prefs")

/**
 * Small local-only settings: which household this device belongs to, and what name to attach
 * to entries this device logs ("logged by Tom" vs a raw user id). Deliberately NOT synced —
 * each device sets its own display name.
 */
class UserPrefs(private val context: Context) {

    private object Keys {
        val HOUSEHOLD_ID = stringPreferencesKey("household_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
    }

    val householdId: Flow<String?> = context.dataStore.data.map { it[Keys.HOUSEHOLD_ID] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }

    suspend fun setHouseholdId(id: String) {
        context.dataStore.edit { it[Keys.HOUSEHOLD_ID] = id }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun clearHousehold() {
        context.dataStore.edit { it.remove(Keys.HOUSEHOLD_ID) }
    }
}
