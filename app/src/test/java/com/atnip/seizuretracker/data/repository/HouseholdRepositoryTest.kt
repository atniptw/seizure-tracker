package com.atnip.seizuretracker.data.repository

import android.os.Looper
import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the real [HouseholdRepository] object against the Firebase Local Emulator Suite —
 * see [FirebaseEmulatorRule]. No mocks: this is the only way to get real confidence out of a
 * Kotlin `object` singleton wired directly to `FirebaseFirestore.getInstance()`.
 */
@RunWith(RobolectricTestRunner::class)
class HouseholdRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private suspend fun firstHousehold(householdId: String): Household =
        HouseholdRepository.observeHousehold(householdId).awaitFirst { it != null }!!

    @Test
    fun `createHousehold produces a unique code, a matching codeIndex doc, and the creator as sole member`() =
        runBlocking {
            withTimeout(5000) {
                val ownerUid = AuthRepository.signInAnonymously()

                val householdId = HouseholdRepository.createHousehold("Rex", ownerUid)
                val household = firstHousehold(householdId)

                assertEquals("Rex", household.dogName)
                assertEquals(listOf(ownerUid), household.members)
                assertEquals(6, household.code.length)

                val resolvedId = HouseholdRepository.findHouseholdIdByCode(household.code)
                assertEquals(householdId, resolvedId)
            }
        }

    @Test
    fun `findHouseholdIdByCode round-trips and normalizes case and whitespace`() = runBlocking {
        withTimeout(5000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid)
            val code = firstHousehold(householdId).code

            assertEquals(householdId, HouseholdRepository.findHouseholdIdByCode(code))
            assertEquals(
                householdId,
                HouseholdRepository.findHouseholdIdByCode("  ${code.lowercase()}  ")
            )
            assertNull(HouseholdRepository.findHouseholdIdByCode("NOTREAL"))
        }
    }

    @Test
    fun `joinHousehold is idempotent on double-join`() = runBlocking {
        withTimeout(5000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid)

            // Simulate a second device: sign out of the anonymous session that created the
            // household and sign in fresh, which mints a brand-new anonymous uid.
            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            assertNotEquals(ownerUid, joinerUid)

            HouseholdRepository.joinHousehold(householdId, joinerUid)
            HouseholdRepository.joinHousehold(householdId, joinerUid)

            val household = firstHousehold(householdId)
            assertEquals(2, household.members.size)
            assertTrue(household.members.containsAll(listOf(ownerUid, joinerUid)))
        }
    }

    @Test
    fun `observeHousehold emits live updates`() = runBlocking {
        withTimeout(5000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid)

            val values = mutableListOf<Household?>()
            val collectJob = launch {
                HouseholdRepository.observeHousehold(householdId).collect { values.add(it) }
            }

            while (values.size < 1) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            assertEquals("Rex", values[0]?.dogName)

            HouseholdRepository.updateDogProfile(
                householdId = householdId,
                dogName = "Max",
                dogBreed = "",
                dogDobMillis = null,
                dogWeightKg = null,
                diagnosisDateMillis = null,
                vetName = "",
                vetPhone = "",
                vetEmail = ""
            )

            while (values.size < 2) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            assertEquals("Max", values[1]?.dogName)

            collectJob.cancel()
        }
    }
}
