@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.dashboard

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HealthNoteRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.PetRepository
import com.atnip.seizuretracker.data.repository.SeizureRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
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
 * Drives the real [DashboardScreen] composable against real [SeizureListViewModel],
 * [HealthNoteListViewModel], and [PetListViewModel] instances wired to the Firebase Local
 * Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var seizureListViewModel: SeizureListViewModel
    private lateinit var healthNoteListViewModel: HealthNoteListViewModel
    private lateinit var petListViewModel: PetListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                householdId = HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
                seizureListViewModel = SeizureListViewModel(householdId)
                healthNoteListViewModel = HealthNoteListViewModel(householdId)
                petListViewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `zero pets shows the add-a-pet empty state and no FAB`() {
        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }

        composeRule.onNodeWithText("Add your first pet to start logging").assertExists()
        composeRule.onNodeWithText("Add a pet").assertExists()
    }

    @Test
    fun `empty state shows no-entries card once a pet exists`(): Unit = runBlocking {
        withTimeout(10000) {
            PetRepository.addPet(householdId, Pet(name = "Bear"))
            petListViewModel.activePet.awaitFirst { it != null }
        }

        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }

        composeRule.onNodeWithText("No entries logged yet.").assertExists()
    }

    @Test
    fun `seeded seizure for the active pet shows in recent entries and the summary card`(): Unit = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            petListViewModel.activePet.awaitFirst { it != null }

            SeizureRepository.addSeizure(
                householdId,
                Seizure(
                    petId = petId,
                    timestampMillis = System.currentTimeMillis(),
                    seizureType = "Focal (partial)",
                    loggedByName = "Tom",
                    durationSeconds = 45
                )
            )
            seizureListViewModel.seizures.awaitFirst { it.isNotEmpty() }
        }

        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }

        composeRule.onNodeWithText("1 seizure logged").assertExists()
        composeRule.onNodeWithText("Last seizure: today").assertExists()
        // The recent-entries row lives inside a LazyColumn, which only composes items within
        // its (small, default-sized under Robolectric) viewport — unlike the summary card above
        // it, it isn't guaranteed to already be in the semantics tree, so scroll the lazy list to
        // it first rather than asserting directly.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Logged by Tom"))
        composeRule.onNodeWithText("Logged by Tom").assertExists()
    }

    @Test
    fun `seeded health note for the active pet shows in recent entries but doesn't count as a seizure`(): Unit = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            petListViewModel.activePet.awaitFirst { it != null }

            HealthNoteRepository.addHealthNote(
                householdId,
                HealthNote(petId = petId, timestampMillis = System.currentTimeMillis(), description = "Limping on back left leg")
            )
            healthNoteListViewModel.healthNotes.awaitFirst { it.isNotEmpty() }
        }

        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }

        composeRule.onNodeWithText("No seizures recorded yet").assertExists()
        composeRule.onNodeWithText("0 seizures logged").assertDoesNotExist()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Limping on back left leg"))
        composeRule.onNodeWithText("Limping on back left leg").assertExists()
    }

    @Test
    fun `seizure for a different pet is not shown on this pet's dashboard`(): Unit = runBlocking {
        withTimeout(10000) {
            val activePetId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            val otherPetId = PetRepository.addPet(householdId, Pet(name = "Milo"))
            petListViewModel.setActivePet(activePetId)
            petListViewModel.activePet.awaitFirst { it?.id == activePetId }

            SeizureRepository.addSeizure(
                householdId,
                Seizure(petId = otherPetId, timestampMillis = System.currentTimeMillis(), notes = "Milo's seizure")
            )
            seizureListViewModel.seizures.awaitFirst { it.isNotEmpty() }
        }

        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }

        composeRule.onNodeWithText("No entries logged yet.").assertExists()
    }
}
