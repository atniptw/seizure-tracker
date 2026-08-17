@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.seizure

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.SeizureRepository
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
 * Drives the real [AddEditSeizureScreen] composable against a real [SeizureListViewModel] wired
 * to the Firebase Local Emulator Suite. See [FirebaseEmulatorRule] and `testutil/FlowTestUtils.kt`'s
 * `awaitFirst` for why both are needed for a screen backed by a live Firestore listener.
 *
 * The form is long enough that the save button sits below the fold of the scrollable column, and
 * this project's `@GraphicsMode(NATIVE)` setup does real touch-based hit testing (not a semantics
 * shortcut) — so every click here goes through `performScrollTo()` first to bring the node into
 * the (Robolectric) viewport before `performClick()`; skipping that makes the click silently hit
 * nothing.
 *
 * The native date/time picker dialogs aren't driven here (see the plan's own risk callout) — only
 * the presence/clickability of the button that opens them is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddEditSeizureScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var viewModel: SeizureListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(10000) {
                // The security rules gate the seizures subcollection on membership of the parent
                // household doc, so a real household is required fixture data even though
                // SeizureListViewModel/SeizureRepository's own APIs only take an id.
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("Rex", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = SeizureListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filling duration and notes and saving adds a seizure with the right fields`() = runBlocking {
        withTimeout(10000) {
            composeRule.setContent {
                AddEditSeizureScreen(
                    navController = rememberNavController(),
                    seizureListViewModel = viewModel,
                    displayName = "Tom",
                    uid = "uid1",
                    activePetId = "pet1",
                    existingSeizureId = null
                )
            }

            composeRule.onNodeWithText("Duration (min)").performTextInput("5")
            composeRule.onNodeWithText("(sec)").performTextInput("30")
            composeRule.onNodeWithText("Additional notes").performTextInput("test notes to save")
            composeRule.onNodeWithText("Save seizure").performScrollTo().performClick()

            val seizures = viewModel.seizures.awaitFirst { it.isNotEmpty() }
            assertEquals(1, seizures.size)
            val saved = seizures[0]
            assertEquals(330L, saved.durationSeconds) // 5*60 + 30
            assertEquals("test notes to save", saved.notes)
            assertEquals("Tom", saved.loggedByName)
            assertEquals("uid1", saved.loggedByUid)
            assertEquals("pet1", saved.petId)
        }
    }

    @Test
    fun `editing a seizure pre-fills fields and saving updates it`() = runBlocking {
        withTimeout(10000) {
            val seeded = Seizure(
                timestampMillis = 1_700_000_000_000L,
                durationSeconds = 125, // 2m 5s
                seizureType = "Focal (partial)",
                notes = "seed notes"
            )
            SeizureRepository.addSeizure(householdId, seeded)
            val seededWithId = viewModel.seizures.awaitFirst { it.isNotEmpty() }[0]

            composeRule.setContent {
                AddEditSeizureScreen(
                    navController = rememberNavController(),
                    seizureListViewModel = viewModel,
                    displayName = "Tom",
                    uid = "uid1",
                    activePetId = "pet1",
                    existingSeizureId = seededWithId.id
                )
            }

            // Pre-filled fields from the seeded seizure.
            composeRule.onNodeWithText("2").assertExists() // minutes
            composeRule.onNodeWithText("5").assertExists() // seconds
            composeRule.onNodeWithText("seed notes").assertExists()

            composeRule.onNodeWithText("seed notes").performTextReplacement("edited notes")
            composeRule.onNodeWithText("Save changes").performScrollTo().performClick()

            val updated = viewModel.seizures.awaitFirst { it.isNotEmpty() && it[0].notes == "edited notes" }
            assertEquals(1, updated.size)
            assertEquals(seededWithId.id, updated[0].id)
            assertEquals(125L, updated[0].durationSeconds)
        }
    }
}
