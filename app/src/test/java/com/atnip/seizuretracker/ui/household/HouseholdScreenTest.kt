@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.household

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [HouseholdScreen] composable against a real [HouseholdViewModel] wired to the
 * Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HouseholdScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var householdId: String
    private lateinit var ownerUid: String
    private lateinit var viewModel: HouseholdViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            withTimeout(5000) {
                ownerUid = AuthRepository.signInAnonymously()
                householdId = HouseholdRepository.createHousehold("The Bear house", ownerUid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = HouseholdViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shows the household code, name, and self with You suffix and no remove icon`(): Unit = runBlocking {
        withTimeout(5000) {
            val household = viewModel.household.awaitFirst { it != null }
            viewModel.members.awaitFirst { it.isNotEmpty() }

            composeRule.setContent {
                HouseholdScreen(navController = rememberNavController(), householdViewModel = viewModel, currentUid = ownerUid)
            }

            composeRule.onNodeWithText(household!!.code).assertExists()
            composeRule.onNodeWithText("The Bear house").assertExists()
            composeRule.onNodeWithText("Members (1)").assertExists()
            composeRule.onNodeWithText("Alex · You").assertExists()
            composeRule.onNodeWithText("No Google account").assertExists()
            composeRule.onNodeWithContentDescription("Remove Alex").assertDoesNotExist()
        }
    }

    @Test
    fun `a non-self member shows a remove icon that opens the confirm dialog and removes them`(): Unit = runBlocking {
        withTimeout(5000) {
            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            shadowOf(Looper.getMainLooper()).idle()
            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)
            viewModel.members.awaitFirst { it.size == 2 }

            composeRule.setContent {
                HouseholdScreen(navController = rememberNavController(), householdViewModel = viewModel, currentUid = ownerUid)
            }

            composeRule.onNodeWithText("Members (2)").assertExists()
            composeRule.onNodeWithText("Sam").assertExists()
            composeRule.onNodeWithContentDescription("Remove Sam").performScrollTo().performClick()

            composeRule.onNodeWithText("Remove Sam from this household?").assertExists()
            composeRule.onNodeWithText("Remove").performClick()

            val members = viewModel.members.awaitFirst { it.size == 1 }
            assertTrue(members.none { it.uid == joinerUid })
        }
    }
}
