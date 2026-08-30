package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [HealthNoteRepository] object against the Firebase Local Emulator Suite —
 * see [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class HealthNoteRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun seedHousehold() = runBlocking {
        withTimeout(10000) {
            val uid = AuthRepository.signInAnonymously()
            householdId = HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
        }
    }

    @Test
    fun `add, update, delete, and getHealthNoteOnce round-trip`() = runBlocking {
        withTimeout(10000) {
            val note = HealthNote(petId = "bear", description = "Limping", timestampMillis = 1_000L)
            HealthNoteRepository.addHealthNote(householdId, note)

            val list = HealthNoteRepository.observeHealthNotes(householdId).awaitFirst { it.isNotEmpty() }
            assertEquals(1, list.size)
            val added = list[0]
            assertEquals("Limping", added.description)
            assertTrue(added.id.isNotBlank())

            val fetched = HealthNoteRepository.getHealthNoteOnce(householdId, added.id)
            assertEquals("Limping", fetched?.description)

            HealthNoteRepository.updateHealthNote(householdId, added.copy(description = "Limping less"))
            val fetchedAfterUpdate = HealthNoteRepository.getHealthNoteOnce(householdId, added.id)
            assertEquals("Limping less", fetchedAfterUpdate?.description)

            HealthNoteRepository.deleteHealthNote(householdId, added.id)
            assertNull(HealthNoteRepository.getHealthNoteOnce(householdId, added.id))
        }
    }

    @Test
    fun `observeHealthNotes orders descending by timestampMillis`() = runBlocking {
        withTimeout(10000) {
            HealthNoteRepository.addHealthNote(householdId, HealthNote(petId = "bear", timestampMillis = 100L, description = "oldest"))
            HealthNoteRepository.addHealthNote(householdId, HealthNote(petId = "bear", timestampMillis = 300L, description = "newest"))
            HealthNoteRepository.addHealthNote(householdId, HealthNote(petId = "bear", timestampMillis = 200L, description = "middle"))

            val list = HealthNoteRepository.observeHealthNotes(householdId).awaitFirst { it.size == 3 }
            assertEquals(listOf("newest", "middle", "oldest"), list.map { it.description })
        }
    }
}
