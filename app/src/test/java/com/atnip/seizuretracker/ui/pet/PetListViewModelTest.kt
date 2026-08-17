@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.pet

import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.PetRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [PetListViewModel] (and, transitively, [PetRepository] and the device-local
 * active-pet preference) against the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 *
 * Uses a 10s timeout budget rather than this codebase's usual 5s: [PetListViewModel.activePet]
 * combines a Firestore listener with a DataStore-backed preference, so several tests here chain
 * multiple real Firestore round-trips *and* a disk-backed DataStore write/read in one body — on a
 * loaded or shared-CI machine that occasionally doesn't clear 5s, even though each individual
 * step is fast.
 */
@RunWith(RobolectricTestRunner::class)
class PetListViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)

    @Test
    fun `activePet is null with no pets`() = runBlocking {
        withTimeout(10000) {
            val viewModel = newViewModel()
            val pets = viewModel.pets.awaitFirst { true }
            assertEquals(emptyList<Pet>(), pets)
            assertNull(viewModel.activePet.value)
        }
    }

    @Test
    fun `activePet self-heals to the oldest pet when nothing is stored`() = runBlocking {
        withTimeout(10000) {
            PetRepository.addPet(householdId, Pet(name = "Milo", createdAtMillis = 200L))
            PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 100L))

            val viewModel = newViewModel()
            val active = viewModel.activePet.awaitFirst { it != null }
            assertEquals("Bear", active?.name)
        }
    }

    @Test
    fun `setActivePet persists and is reflected by a fresh ViewModel instance`() = runBlocking {
        withTimeout(10000) {
            val bearId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            PetRepository.addPet(householdId, Pet(name = "Milo"))

            val viewModel = newViewModel()
            viewModel.pets.awaitFirst { it.size == 2 }
            viewModel.setActivePet(bearId)
            viewModel.activePet.awaitFirst { it?.id == bearId }

            // A brand-new ViewModel instance (simulating e.g. an app restart) reads the same
            // persisted preference, not device/process-local state.
            val freshViewModel = newViewModel()
            val active = freshViewModel.activePet.awaitFirst { it != null }
            assertEquals("Bear", active?.name)
        }
    }

    @Test
    fun `activePet self-heals when the stored id no longer matches any pet`() = runBlocking {
        withTimeout(10000) {
            val bearId = PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 100L))

            val viewModel = newViewModel()
            viewModel.setActivePet(bearId)
            viewModel.activePet.awaitFirst { it?.id == bearId }

            PetRepository.deletePet(householdId, bearId)
            PetRepository.addPet(householdId, Pet(name = "Milo", createdAtMillis = 200L))

            val active = viewModel.activePet.awaitFirst { it?.name == "Milo" }
            assertEquals("Milo", active?.name)
        }
    }

    @Test
    fun `addPet, updatePet, and deletePet round-trip through the ViewModel`() = runBlocking {
        withTimeout(10000) {
            val viewModel = newViewModel()
            var addedId: String? = null
            viewModel.addPet(Pet(name = "Bear")) { id -> addedId = id }

            val pets = viewModel.pets.awaitFirst { it.isNotEmpty() }
            assertEquals("Bear", pets[0].name)
            val petId = addedId!!

            viewModel.updatePet(pets[0].copy(name = "Bear Jr."))
            val renamed = viewModel.pets.awaitFirst { it.firstOrNull()?.name == "Bear Jr." }
            assertEquals("Bear Jr.", renamed[0].name)

            viewModel.deletePet(petId)
            val afterDelete = viewModel.pets.awaitFirst { it.isEmpty() }
            assertEquals(emptyList<Pet>(), afterDelete)
        }
    }
}
