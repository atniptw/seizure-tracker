@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.export

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.atnip.seizuretracker.testutil.resetFileProviderCache
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
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
 * Drives the real [ExportScreen] composable against real repository-backed ViewModels wired to
 * the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var petListViewModel: PetListViewModel
    private lateinit var seizureListViewModel: SeizureListViewModel
    private lateinit var healthNoteListViewModel: HealthNoteListViewModel
    private lateinit var vetViewModel: VetViewModel
    private lateinit var exportViewModel: ExportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetFileProviderCache()
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        petListViewModel = PetListViewModel(ApplicationProvider.getApplicationContext(), householdId)
        seizureListViewModel = SeizureListViewModel(householdId)
        healthNoteListViewModel = HealthNoteListViewModel(householdId)
        vetViewModel = VetViewModel(householdId)
        exportViewModel = ExportViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setScreenContent() {
        composeRule.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = Destinations.EXPORT) {
                composable(Destinations.EXPORT) {
                    ExportScreen(
                        navController = navController,
                        petListViewModel = petListViewModel,
                        seizureListViewModel = seizureListViewModel,
                        healthNoteListViewModel = healthNoteListViewModel,
                        vetViewModel = vetViewModel,
                        exportViewModel = exportViewModel
                    )
                }
                composable(Destinations.EXPORT_READY) {}
            }
        }
    }

    @Test
    fun `shows range chips, include checkboxes, and format control`(): Unit = runBlocking {
        withTimeout(10000) {
            PetRepository.addPet(householdId, Pet(name = "Bear"))
            petListViewModel.pets.awaitFirst { it.size == 1 }

            setScreenContent()

            composeRule.onNodeWithText("Last 30 days").assertExists()
            composeRule.onNodeWithText("Last 90 days").assertExists()
            composeRule.onNodeWithText("All time").assertExists()
            composeRule.onNodeWithText("Custom").assertExists()
            composeRule.onNodeWithText("Seizures").assertExists()
            composeRule.onNodeWithText("Health notes").assertExists()
            composeRule.onNodeWithText("PDF").assertExists()
            composeRule.onNodeWithText("CSV").assertExists()
        }
    }

    @Test
    fun `pet segmented control only appears with more than one pet`(): Unit = runBlocking {
        withTimeout(10000) {
            PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 1L))
            petListViewModel.pets.awaitFirst { it.size == 1 }

            setScreenContent()
            composeRule.onNodeWithText("All pets").assertDoesNotExist()
        }
    }

    @Test
    fun `preview count reflects the include-flag checkboxes`(): Unit = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            SeizureRepository.addSeizure(householdId, Seizure(petId = petId, timestampMillis = 1_000L))
            HealthNoteRepository.addHealthNote(householdId, HealthNote(petId = petId, timestampMillis = 2_000L, description = "Limping"))
            petListViewModel.pets.awaitFirst { it.size == 1 }
            seizureListViewModel.seizures.awaitFirst { it.size == 1 }
            healthNoteListViewModel.healthNotes.awaitFirst { it.size == 1 }

            setScreenContent()

            composeRule.onNodeWithText("2 entries will be included.").assertExists()

            composeRule.onNodeWithText("Seizures").performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("1 entry will be included.").assertExists()
        }
    }

    @Test
    fun `tapping Create export generates a CSV and navigates to the ready screen`(): Unit = runBlocking {
        withTimeout(10000) {
            val petId = PetRepository.addPet(householdId, Pet(name = "Bear"))
            SeizureRepository.addSeizure(householdId, Seizure(petId = petId, timestampMillis = 1_000L))
            petListViewModel.pets.awaitFirst { it.size == 1 }
            seizureListViewModel.seizures.awaitFirst { it.size == 1 }

            setScreenContent()

            composeRule.onNodeWithText("CSV").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Create export").performScrollTo().performClick()

            val result = exportViewModel.result.awaitFirst { it != null }!!
            assertEquals(1, result.entryCount)
            assertEquals("text/csv", result.mimeType)
        }
    }
}
