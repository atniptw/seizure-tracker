@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.pet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.atnip.seizuretracker.ui.vet.VetViewModel
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [AddEditPetScreen] composable against a real [PetListViewModel] wired to the
 * Firebase Local Emulator Suite. See [FirebaseEmulatorRule] and `testutil/FlowTestUtils.kt`'s
 * `awaitFirst` for why both are needed for a screen backed by a live Firestore listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddEditPetScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var viewModel: PetListViewModel
    private lateinit var vetViewModel: VetViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
        vetViewModel = VetViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filling name, species, and breed and saving adds a pet`() = runBlocking {
        withTimeout(5000) {
            composeRule.setContent {
                AddEditPetScreen(navController = rememberNavController(), petListViewModel = viewModel, vetViewModel = vetViewModel, existingPetId = null)
            }

            composeRule.onNodeWithText("Name").performTextInput("Milo")
            composeRule.onNodeWithText("Cat").performClick()
            composeRule.onNodeWithText("Breed").performTextInput("Tabby")
            composeRule.onNodeWithText("Save").performScrollTo().performClick()

            val pets = viewModel.pets.awaitFirst { it.isNotEmpty() }
            assertEquals(1, pets.size)
            assertEquals("Milo", pets[0].name)
            assertEquals("Cat", pets[0].species)
            assertEquals("Tabby", pets[0].breed)
        }
    }

    @Test
    fun `adding the first pet makes it the active pet`() = runBlocking {
        withTimeout(5000) {
            composeRule.setContent {
                AddEditPetScreen(navController = rememberNavController(), petListViewModel = viewModel, vetViewModel = vetViewModel, existingPetId = null)
            }

            composeRule.onNodeWithText("Name").performTextInput("Bear")
            composeRule.onNodeWithText("Save").performScrollTo().performClick()

            val active = viewModel.activePet.awaitFirst { it != null }
            assertEquals("Bear", active?.name)
        }
    }

    @Test
    fun `editing a pet pre-fills fields and saving updates it`() = runBlocking {
        withTimeout(5000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear", species = "Dog", breed = "Lab"))
            viewModel.pets.awaitFirst { it.isNotEmpty() }

            composeRule.setContent {
                AddEditPetScreen(navController = rememberNavController(), petListViewModel = viewModel, vetViewModel = vetViewModel, existingPetId = petId)
            }

            composeRule.onNodeWithText("Bear").assertExists()
            composeRule.onNodeWithText("Lab").assertExists()

            composeRule.onNodeWithText("Bear").performTextReplacement("Bear Jr.")
            composeRule.onNodeWithText("Save").performScrollTo().performClick()

            val updated = viewModel.pets.awaitFirst { it.firstOrNull()?.name == "Bear Jr." }
            assertEquals(petId, updated[0].id)
            assertEquals("Dog", updated[0].species)
        }
    }

    @Test
    fun `editing a pet shows its linked vets`(): Unit = runBlocking {
        withTimeout(5000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            val vetId = VetRepository.addVet(householdId, Vet(name = "Riverside Animal Hospital"))
            PetVetLinkRepository.addLink(householdId, petId, vetId, "General")
            viewModel.pets.awaitFirst { it.isNotEmpty() }
            vetViewModel.vets.awaitFirst { it.isNotEmpty() }
            vetViewModel.links.awaitFirst { it.isNotEmpty() }

            composeRule.setContent {
                AddEditPetScreen(navController = rememberNavController(), petListViewModel = viewModel, vetViewModel = vetViewModel, existingPetId = petId)
            }

            composeRule.onNodeWithText("Riverside Animal Hospital · General").assertExists()
            composeRule.onNodeWithText("+ Link a vet").assertExists()
        }
    }
}
