@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.household

import com.atnip.seizuretracker.data.model.Medication
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [HouseholdViewModel] (and, transitively, [HouseholdRepository]) against
 * the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class HouseholdViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("Rex", uid)
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `household StateFlow eventually reflects the seeded household`() = runBlocking {
        withTimeout(5000) {
            val viewModel = HouseholdViewModel(householdId)

            val household = viewModel.household.awaitFirst { it != null }
            assertEquals("Rex", household?.dogName)
            assertEquals(householdId, household?.id)
        }
    }

    @Test
    fun `updateDogProfile updates Firestore and the StateFlow reflects it`() = runBlocking {
        withTimeout(5000) {
            val viewModel = HouseholdViewModel(householdId)
            viewModel.household.awaitFirst { it != null }

            viewModel.updateDogProfile(
                dogName = "Max",
                dogBreed = "Lab",
                dogDobMillis = 12345L,
                dogWeightKg = 30.5,
                diagnosisDateMillis = 67890L,
                vetName = "Dr. Smith",
                vetPhone = "555-1234",
                vetEmail = "vet@example.com"
            )

            val updated = viewModel.household.awaitFirst { it?.dogName == "Max" }
            assertEquals("Lab", updated?.dogBreed)
            assertEquals(12345L, updated?.dogDobMillis)
            assertEquals(30.5, updated?.dogWeightKg)
            assertEquals("Dr. Smith", updated?.vetName)
        }
    }

    @Test
    fun `updateMedications updates Firestore and the StateFlow reflects it`() = runBlocking {
        withTimeout(5000) {
            val viewModel = HouseholdViewModel(householdId)
            viewModel.household.awaitFirst { it != null }

            val meds = listOf(
                Medication(name = "Phenobarbital", dose = "50mg", frequency = "2x daily", notes = "with food")
            )
            viewModel.updateMedications(meds)

            val updated = viewModel.household.awaitFirst { it?.medications?.isNotEmpty() == true }
            assertEquals(meds, updated?.medications)
        }
    }
}
