@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.vet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [VetDetailScreen] composable against real [VetViewModel] and
 * [PetListViewModel] instances wired to the Firebase Local Emulator Suite. See
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VetDetailScreenTest {

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
            withTimeout(10000) {
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
    fun `filling name and phone and saving adds a vet`() = runBlocking {
        withTimeout(10000) {
            composeRule.setContent {
                VetDetailScreen(
                    navController = rememberNavController(),
                    vetViewModel = vetViewModel,
                    petListViewModel = petListViewModel,
                    existingVetId = null
                )
            }

            composeRule.onNodeWithText("Name").performTextInput("Riverside Animal Hospital")
            composeRule.onNodeWithText("Phone").performTextInput("555-0192")
            composeRule.onNodeWithText("Save").performScrollTo().performClick()

            val vets = vetViewModel.vets.awaitFirst { it.isNotEmpty() }
            assertEquals("Riverside Animal Hospital", vets[0].name)
            assertEquals("555-0192", vets[0].phone)
        }
    }

    @Test
    fun `adding a vet with linkToPetId also links it to that pet with the default role`() = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            petListViewModel.pets.awaitFirst { it.isNotEmpty() }

            composeRule.setContent {
                VetDetailScreen(
                    navController = rememberNavController(),
                    vetViewModel = vetViewModel,
                    petListViewModel = petListViewModel,
                    existingVetId = null,
                    linkToPetId = petId
                )
            }

            composeRule.onNodeWithText("Name").performTextInput("Riverside Animal Hospital")
            composeRule.onNodeWithText("Save").performScrollTo().performClick()

            val vets = vetViewModel.vets.awaitFirst { it.isNotEmpty() }
            val links = vetViewModel.links.awaitFirst { it.isNotEmpty() }
            assertEquals(petId, links[0].petId)
            assertEquals(vets[0].id, links[0].vetId)
            assertEquals("General", links[0].role)
        }
    }

    @Test
    fun `editing a vet shows its linked pets and role, and removing it deletes it`() = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            val vetId = VetRepository.addVet(householdId, Vet(name = "Riverside Animal Hospital", phone = "555-0192"))
            PetVetLinkRepository.addLink(householdId, petId, vetId, "General")

            petListViewModel.pets.awaitFirst { it.isNotEmpty() }
            vetViewModel.vets.awaitFirst { it.isNotEmpty() }
            vetViewModel.links.awaitFirst { it.isNotEmpty() }

            composeRule.setContent {
                VetDetailScreen(
                    navController = rememberNavController(),
                    vetViewModel = vetViewModel,
                    petListViewModel = petListViewModel,
                    existingVetId = vetId
                )
            }

            // "Riverside Animal Hospital" is deliberately not asserted by text here — it
            // ambiguously matches both the pre-filled Name field and the TopAppBar title (which
            // shows the vet's own name while editing).
            composeRule.onNodeWithText("Bear").assertExists()
            composeRule.onNodeWithText("General ▾").assertExists()

            composeRule.onNodeWithText("Remove this vet").performScrollTo().performClick()
            composeRule.onNodeWithText("Remove").performClick()

            val vets = vetViewModel.vets.awaitFirst { it.isEmpty() }
            assertTrue(vets.isEmpty())
        }
    }
}
