@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.seizure

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Seizure
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [SeizureListViewModel] (and, transitively, the real SeizureRepository)
 * against the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class SeizureListViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String
    private lateinit var viewModel: SeizureListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                // SeizureRepository only needs a valid household id, but the security rules
                // gate the seizures subcollection on membership of the parent household doc,
                // so a real (if minimal) household fixture is required regardless.
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("Rex", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = SeizureListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addSeizure is reflected in seizures`() = runBlocking {
        withTimeout(10000) {
            viewModel.addSeizure(Seizure(timestampMillis = 1_000L, notes = "first"))

            val seizures = viewModel.seizures.awaitFirst { it.isNotEmpty() }
            assertEquals(1, seizures.size)
            assertEquals("first", seizures[0].notes)
        }
    }

    @Test
    fun `updateSeizure and deleteSeizure are reflected in seizures`() = runBlocking {
        withTimeout(10000) {
            viewModel.addSeizure(Seizure(timestampMillis = 1_000L, notes = "first"))
            val seeded = viewModel.seizures.awaitFirst { it.isNotEmpty() }[0]

            viewModel.updateSeizure(seeded.copy(notes = "updated"))
            val afterUpdate = viewModel.seizures.awaitFirst { it.isNotEmpty() && it[0].notes == "updated" }
            assertEquals(1, afterUpdate.size)

            viewModel.deleteSeizure(afterUpdate[0].id)
            val afterDelete = viewModel.seizures.awaitFirst { it.isEmpty() }
            assertTrue(afterDelete.isEmpty())
        }
    }
}
