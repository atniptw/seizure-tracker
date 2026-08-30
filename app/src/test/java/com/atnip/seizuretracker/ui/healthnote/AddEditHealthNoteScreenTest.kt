@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.healthnote

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HealthNoteRepository
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [AddEditHealthNoteScreen] composable against a real [HealthNoteListViewModel]
 * wired to the Firebase Local Emulator Suite. See [FirebaseEmulatorRule] and
 * `testutil/FlowTestUtils.kt`'s `awaitFirst` for why both are needed for a screen backed by a
 * live Firestore listener.
 *
 * The native date/time picker dialog isn't driven here (same unexercised-native-dialog
 * convention as `AddEditSeizureScreenTest`) — only the presence/clickability of the button
 * that opens it is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddEditHealthNoteScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var viewModel: HealthNoteListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = HealthNoteListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filling description and notes and saving adds a health note`() = runBlocking {
        withTimeout(10000) {
            composeRule.setContent {
                AddEditHealthNoteScreen(
                    navController = rememberNavController(),
                    healthNoteListViewModel = viewModel,
                    displayName = "Tom",
                    uid = "uid1",
                    activePetId = "pet1",
                    existingNoteId = null
                )
            }

            composeRule.onNodeWithText("What's going on?").performTextInput("Limping on back left leg")
            composeRule.onNodeWithText("Notes").performTextInput("Started after the walk")
            composeRule.onNodeWithText("Save note").performScrollTo().performClick()

            val notes = viewModel.healthNotes.awaitFirst { it.isNotEmpty() }
            assertEquals(1, notes.size)
            val saved = notes[0]
            assertEquals("Limping on back left leg", saved.description)
            assertEquals("Started after the walk", saved.notes)
            assertEquals("Tom", saved.loggedByName)
            assertEquals("uid1", saved.loggedByUid)
            assertEquals("pet1", saved.petId)
        }
    }

    @Test
    fun `editing a health note pre-fills fields and saving updates it`() = runBlocking {
        withTimeout(10000) {
            val seeded = HealthNote(
                petId = "pet1",
                timestampMillis = 1_700_000_000_000L,
                description = "Limping on back left leg",
                notes = "seed notes"
            )
            HealthNoteRepository.addHealthNote(householdId, seeded)
            val seededWithId = viewModel.healthNotes.awaitFirst { it.isNotEmpty() }[0]

            composeRule.setContent {
                AddEditHealthNoteScreen(
                    navController = rememberNavController(),
                    healthNoteListViewModel = viewModel,
                    displayName = "Tom",
                    uid = "uid1",
                    activePetId = "pet1",
                    existingNoteId = seededWithId.id
                )
            }

            composeRule.onNodeWithText("Limping on back left leg").assertExists()
            composeRule.onNodeWithText("seed notes").assertExists()

            composeRule.onNodeWithText("seed notes").performTextReplacement("edited notes")
            composeRule.onNodeWithText("Save note").performScrollTo().performClick()

            val updated = viewModel.healthNotes.awaitFirst { it.isNotEmpty() && it[0].notes == "edited notes" }
            assertEquals(1, updated.size)
            assertEquals(seededWithId.id, updated[0].id)
        }
    }
}
