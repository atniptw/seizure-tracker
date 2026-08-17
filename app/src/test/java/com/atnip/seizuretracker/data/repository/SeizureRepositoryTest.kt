package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Seizure
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
 * Exercises the real [SeizureRepository] object against the Firebase Local Emulator Suite — see
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class SeizureRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun seedHousehold() = runBlocking {
        withTimeout(5000) {
            // The security rules gate the seizures subcollection on membership of the parent
            // household doc, so a real household (with this signed-in uid as a member) is
            // required fixture data even though SeizureRepository's own API only takes an id.
            val uid = AuthRepository.signInAnonymously()
            householdId = HouseholdRepository.createHousehold("Rex", uid, "Alex", AuthMethods.ANONYMOUS)
        }
    }

    @Test
    fun `add, update, delete, and getSeizureOnce round-trip`() = runBlocking {
        withTimeout(5000) {
            val seizure = Seizure(
                timestampMillis = 1_000L,
                durationSeconds = 30,
                seizureType = "Focal (partial)",
                notes = "first"
            )
            SeizureRepository.addSeizure(householdId, seizure)

            val list = SeizureRepository.observeSeizures(householdId).awaitFirst { it.isNotEmpty() }
            assertEquals(1, list.size)
            val added = list[0]
            assertEquals("first", added.notes)
            assertTrue(added.id.isNotBlank())

            val fetched = SeizureRepository.getSeizureOnce(householdId, added.id)
            assertEquals("first", fetched?.notes)

            SeizureRepository.updateSeizure(householdId, added.copy(notes = "updated"))
            val fetchedAfterUpdate = SeizureRepository.getSeizureOnce(householdId, added.id)
            assertEquals("updated", fetchedAfterUpdate?.notes)

            SeizureRepository.deleteSeizure(householdId, added.id)
            val fetchedAfterDelete = SeizureRepository.getSeizureOnce(householdId, added.id)
            assertNull(fetchedAfterDelete)
        }
    }

    @Test
    fun `observeSeizures orders descending by timestampMillis`() = runBlocking {
        withTimeout(5000) {
            SeizureRepository.addSeizure(householdId, Seizure(timestampMillis = 100L, notes = "oldest"))
            SeizureRepository.addSeizure(householdId, Seizure(timestampMillis = 300L, notes = "newest"))
            SeizureRepository.addSeizure(householdId, Seizure(timestampMillis = 200L, notes = "middle"))

            val list = SeizureRepository.observeSeizures(householdId).awaitFirst { it.size == 3 }
            assertEquals(listOf("newest", "middle", "oldest"), list.map { it.notes })
        }
    }

    @Test
    fun `petId round-trips through add and update`() = runBlocking {
        withTimeout(5000) {
            val seizure = Seizure(petId = "pet-1", timestampMillis = 1_000L, notes = "first")
            SeizureRepository.addSeizure(householdId, seizure)

            val added = SeizureRepository.observeSeizures(householdId).awaitFirst { it.isNotEmpty() }[0]
            assertEquals("pet-1", added.petId)

            SeizureRepository.updateSeizure(householdId, added.copy(petId = "pet-2"))
            val fetched = SeizureRepository.getSeizureOnce(householdId, added.id)
            assertEquals("pet-2", fetched?.petId)
        }
    }
}
