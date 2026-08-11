@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.dashboard

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.data.repository.SeizureRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.household.HouseholdViewModel
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
 * Drives the real [DashboardScreen] composable against real [HouseholdViewModel] and
 * [SeizureListViewModel] instances wired to the Firebase Local Emulator Suite. See
 * [FirebaseEmulatorRule].
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
    private lateinit var householdViewModel: HouseholdViewModel
    private lateinit var seizureListViewModel: SeizureListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                householdId = HouseholdRepository.createHousehold("Rex", uid)
                householdViewModel = HouseholdViewModel(householdId)
                seizureListViewModel = SeizureListViewModel(householdId)
                householdViewModel.household.awaitFirst { it != null }
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty state shows no-seizures card`() {
        composeRule.setContent {
            DashboardScreen(
                navController = rememberNavController(),
                householdViewModel = householdViewModel,
                seizureListViewModel = seizureListViewModel
            )
        }

        composeRule.onNodeWithText("No seizures logged yet.").assertExists()
    }

    @Test
    fun `seeded seizure shows in recent seizures and the summary card`(): Unit = runBlocking {
        withTimeout(5000) {
            SeizureRepository.addSeizure(
                householdId,
                Seizure(
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
                householdViewModel = householdViewModel,
                seizureListViewModel = seizureListViewModel
            )
        }

        composeRule.onNodeWithText("1 total logged").assertExists()
        composeRule.onNodeWithText("Last seizure: today").assertExists()
        // The recent-seizures row lives inside a LazyColumn, which only composes items within
        // its (small, default-sized under Robolectric) viewport — unlike the summary card above
        // it, it isn't guaranteed to already be in the semantics tree, so scroll the lazy list to
        // it first rather than asserting directly.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Logged by Tom"))
        composeRule.onNodeWithText("Logged by Tom").assertExists()
    }
}
