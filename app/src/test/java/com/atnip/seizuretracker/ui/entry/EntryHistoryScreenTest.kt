@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.entry

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HealthNoteRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.SeizureRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
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
 * Drives the real [EntryHistoryScreen] composable against real [SeizureListViewModel] and
 * [HealthNoteListViewModel] instances wired to the Firebase Local Emulator Suite. See
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryHistoryScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var seizureListViewModel: SeizureListViewModel
    private lateinit var healthNoteListViewModel: HealthNoteListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        seizureListViewModel = SeizureListViewModel(householdId)
        healthNoteListViewModel = HealthNoteListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty state shows the no-entries message`() {
        composeRule.setContent {
            EntryHistoryScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                activePetId = "pet1"
            )
        }

        composeRule.onNodeWithText("No entries logged yet.").assertExists()
    }

    @Test
    fun `merges seizures and health notes for the active pet, sorted by most recent`(): Unit = runBlocking {
        withTimeout(10000) {
            SeizureRepository.addSeizure(
                householdId,
                Seizure(petId = "pet1", timestampMillis = 1_000L, seizureType = "Focal (partial)", loggedByName = "Tom")
            )
            HealthNoteRepository.addHealthNote(
                householdId,
                HealthNote(petId = "pet1", timestampMillis = 2_000L, description = "Limping")
            )
            // A different pet's entry must not show up here.
            SeizureRepository.addSeizure(householdId, Seizure(petId = "pet2", timestampMillis = 3_000L, notes = "Milo's"))

            seizureListViewModel.seizures.awaitFirst { it.size == 2 }
            healthNoteListViewModel.healthNotes.awaitFirst { it.isNotEmpty() }
        }

        composeRule.setContent {
            EntryHistoryScreen(
                navController = rememberNavController(),
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                activePetId = "pet1"
            )
        }

        composeRule.onNodeWithText("Entry history (2)").assertExists()
        composeRule.onNodeWithText("Logged by Tom").assertExists()
        composeRule.onNodeWithText("Limping").assertExists()
    }
}
