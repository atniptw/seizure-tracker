@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.vet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.PetRepository
import com.atnip.seizuretracker.data.repository.PetVetLinkRepository
import com.atnip.seizuretracker.data.repository.VetRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [VetsDirectoryScreen] composable against real [VetViewModel] and
 * [PetListViewModel] instances wired to the Firebase Local Emulator Suite. See
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VetsDirectoryScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var vetViewModel: VetViewModel
    private lateinit var petListViewModel: PetListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        vetViewModel = VetViewModel(householdId)
        petListViewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty state shows the no-vets message`() {
        composeRule.setContent {
            VetsDirectoryScreen(navController = rememberNavController(), vetViewModel = vetViewModel, petListViewModel = petListViewModel)
        }

        composeRule.onNodeWithText("No vets in the household directory yet.").assertExists()
    }

    @Test
    fun `shows a vet with its role tags resolved to pet names`(): Unit = runBlocking {
        withTimeout(5000) {
            val bearId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            val miloId = PetRepository.addPet(householdId, Pet(name = "Milo"))
            val vetId = VetRepository.addVet(householdId, Vet(name = "Riverside Animal Hospital", phone = "555-0192"))
            PetVetLinkRepository.addLink(householdId, bearId, vetId, "General")
            PetVetLinkRepository.addLink(householdId, miloId, vetId, "Emergency")

            petListViewModel.pets.awaitFirst { it.size == 2 }
            vetViewModel.vets.awaitFirst { it.isNotEmpty() }
            vetViewModel.links.awaitFirst { it.size == 2 }
        }

        composeRule.setContent {
            VetsDirectoryScreen(navController = rememberNavController(), vetViewModel = vetViewModel, petListViewModel = petListViewModel)
        }

        composeRule.onNodeWithText("Riverside Animal Hospital").assertExists()
        composeRule.onNodeWithText("555-0192").assertExists()
        composeRule.onNodeWithText("Bear · General").assertExists()
        composeRule.onNodeWithText("Milo · Emergency").assertExists()
    }
}
