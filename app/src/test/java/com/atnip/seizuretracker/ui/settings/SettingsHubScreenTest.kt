@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.PetRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.session.SessionState
import com.atnip.seizuretracker.ui.session.SessionViewModel
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
 * Drives the real [SettingsHubScreen] composable against real [PetListViewModel] and
 * [SessionViewModel] instances wired to the Firebase Local Emulator Suite. See
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHubScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var petListViewModel: PetListViewModel
    private lateinit var session: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        petListViewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
        session = SessionViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lists every settings row`() {
        composeRule.setContent {
            SettingsHubScreen(navController = rememberNavController(), petListViewModel = petListViewModel, session = session)
        }

        composeRule.onNodeWithText("Pets").assertExists()
        composeRule.onNodeWithText("Vets").assertExists()
        composeRule.onNodeWithText("Household").assertExists()
        composeRule.onNodeWithText("Accessibility").assertExists()
        composeRule.onNodeWithText("Export for vet").assertExists()
        composeRule.onNodeWithText("Sign out").assertExists()
    }

    @Test
    fun `Pets subtitle lists every pet's name`(): Unit = runBlocking {
        withTimeout(10000) {
            // Distinct createdAtMillis: addPet doesn't stamp one server-side, and the pets list
            // is ordered by it, so two default (0L) values would tie and sort arbitrarily.
            PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 1L))
            PetRepository.addPet(householdId, Pet(name = "Milo", createdAtMillis = 2L))
            petListViewModel.pets.awaitFirst { it.size == 2 }
        }

        composeRule.setContent {
            SettingsHubScreen(navController = rememberNavController(), petListViewModel = petListViewModel, session = session)
        }

        composeRule.onNodeWithText("Bear, Milo — edit any pet, add a new one").assertExists()
    }

    @Test
    fun `tapping Sign out signs out and returns the session to NeedsSetup`(): Unit = runBlocking {
        withTimeout(10000) {
            composeRule.setContent {
                SettingsHubScreen(navController = rememberNavController(), petListViewModel = petListViewModel, session = session)
            }

            composeRule.onNodeWithText("Sign out").performClick()

            val state = session.state.awaitFirst { it is SessionState.NeedsSetup }
            org.junit.Assert.assertTrue(state is SessionState.NeedsSetup)
        }
    }
}
