@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.seizure

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [SeizureHistoryScreen] composable against a real [SeizureListViewModel] wired
 * to the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeizureHistoryScreenTest {

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
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("Rex", uid)
            }
        }
        viewModel = SeizureListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty state shows the no-seizures message`() {
        composeRule.setContent {
            SeizureHistoryScreen(navController = rememberNavController(), seizureListViewModel = viewModel)
        }

        composeRule.onNodeWithText("No seizures logged yet.").assertExists()
    }

    @Test
    fun `populated state shows the count and seizure rows`(): Unit = runBlocking {
        withTimeout(5000) {
            SeizureRepository.addSeizure(
                householdId,
                Seizure(timestampMillis = 1_000L, seizureType = "Focal (partial)", loggedByName = "Tom", durationSeconds = 30)
            )
            SeizureRepository.addSeizure(
                householdId,
                Seizure(timestampMillis = 2_000L, seizureType = "Petit mal / absence", loggedByName = "Alice", durationSeconds = 60)
            )
            viewModel.seizures.awaitFirst { it.size == 2 }
        }

        composeRule.setContent {
            SeizureHistoryScreen(navController = rememberNavController(), seizureListViewModel = viewModel)
        }

        composeRule.onNodeWithText("Seizure history (2)").assertExists()
        composeRule.onNodeWithText("Logged by Tom").assertExists()
        composeRule.onNodeWithText("Logged by Alice").assertExists()
    }
}
