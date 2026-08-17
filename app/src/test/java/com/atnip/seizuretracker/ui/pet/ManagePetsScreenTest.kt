@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.pet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atnip.seizuretracker.ui.navigation.Destinations
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [ManagePetsScreen] composable against a real [PetListViewModel] wired to the
 * Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagePetsScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var viewModel: PetListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lists every pet, marks the active one as Current, and shows Add a pet`(): Unit = runBlocking {
        withTimeout(10000) {
            val bearId = PetRepository.addPet(householdId, Pet(name = "Bear", species = "Dog", createdAtMillis = 100L))
            PetRepository.addPet(householdId, Pet(name = "Milo", species = "Cat", createdAtMillis = 200L))
            viewModel.setActivePet(bearId)
            viewModel.activePet.awaitFirst { it?.id == bearId }
            viewModel.pets.awaitFirst { it.size == 2 }

            composeRule.setContent {
                ManagePetsScreen(navController = rememberNavController(), petListViewModel = viewModel)
            }

            composeRule.onNodeWithText("Bear · Current").assertExists()
            composeRule.onNodeWithText("Milo").assertExists()
            composeRule.onNodeWithText("Add a pet").assertExists()
        }
    }

    @Test
    fun `tapping a non-active pet does not change the active pet`() = runBlocking {
        withTimeout(10000) {
            val bearId = PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 100L))
            PetRepository.addPet(householdId, Pet(name = "Milo", createdAtMillis = 200L))
            viewModel.setActivePet(bearId)
            viewModel.activePet.awaitFirst { it?.id == bearId }
            viewModel.pets.awaitFirst { it.size == 2 }

            // A real NavHost with both routes, so tapping a pet's real navigate() call (which
            // ManagePetsScreen fires directly on the NavController) has a graph to resolve
            // against instead of crashing on a bare, graph-less rememberNavController().
            composeRule.setContent {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Destinations.MANAGE_PETS) {
                    composable(Destinations.MANAGE_PETS) {
                        ManagePetsScreen(navController = navController, petListViewModel = viewModel)
                    }
                    composable(
                        Destinations.EDIT_PET,
                        arguments = listOf(navArgument("petId") { type = NavType.StringType })
                    ) {}
                }
            }

            // Tapping a non-active pet opens its edit screen but must not itself change which
            // pet is active — only the pet switcher does.
            composeRule.onNodeWithText("Milo").performClick()
            composeRule.waitForIdle()

            org.junit.Assert.assertEquals(bearId, viewModel.activePet.value?.id)
        }
    }
}
