@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.vet

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Vet
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
 * Exercises the real [VetViewModel] (and, transitively, [VetRepository] and
 * [PetVetLinkRepository]) against the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class VetViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String
    private lateinit var viewModel: VetViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = VetViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addVet and updateVet round-trip through the ViewModel`() = runBlocking {
        withTimeout(5000) {
            var addedId: String? = null
            viewModel.addVet(Vet(name = "Riverside Animal Hospital", phone = "555-0192")) { id -> addedId = id }

            val vets = viewModel.vets.awaitFirst { it.isNotEmpty() }
            assertEquals("555-0192", vets[0].phone)

            viewModel.updateVet(vets[0].copy(phone = "555-0000"))
            val updated = viewModel.vets.awaitFirst { it.firstOrNull()?.phone == "555-0000" }
            assertEquals(addedId, updated[0].id)
        }
    }

    @Test
    fun `addLink, updateLinkRole, and removeLink round-trip through the ViewModel`() = runBlocking {
        withTimeout(5000) {
            var vetId: String? = null
            viewModel.addVet(Vet(name = "Riverside Animal Hospital")) { id -> vetId = id }
            viewModel.vets.awaitFirst { it.isNotEmpty() }

            viewModel.addLink("bear", vetId!!, "General")
            var links = viewModel.links.awaitFirst { it.isNotEmpty() }
            assertEquals("General", links[0].role)

            viewModel.updateLinkRole(links[0].id, "Emergency")
            links = viewModel.links.awaitFirst { it.firstOrNull()?.role == "Emergency" }
            assertEquals("Emergency", links[0].role)

            viewModel.removeLink(links[0].id)
            val afterRemove = viewModel.links.awaitFirst { it.isEmpty() }
            assertTrue(afterRemove.isEmpty())
        }
    }

    @Test
    fun `deleteVet removes the vet and its links`() = runBlocking {
        withTimeout(5000) {
            var vetId: String? = null
            viewModel.addVet(Vet(name = "Riverside Animal Hospital")) { id -> vetId = id }
            viewModel.vets.awaitFirst { it.isNotEmpty() }
            viewModel.addLink("bear", vetId!!, "General")
            viewModel.links.awaitFirst { it.isNotEmpty() }

            viewModel.deleteVet(vetId!!)

            val vets = viewModel.vets.awaitFirst { it.isEmpty() }
            assertTrue(vets.isEmpty())
            val links = viewModel.links.awaitFirst { it.isEmpty() }
            assertTrue(links.isEmpty())
        }
    }
}
