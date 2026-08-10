@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.welcome

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.ui.session.SessionState
import com.atnip.seizuretracker.ui.session.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real [WelcomeScreen] composable against a real [SessionViewModel] wired to the
 * Firebase Local Emulator Suite. See [FirebaseEmulatorRule] and `testutil/FlowTestUtils.kt`'s
 * `awaitFirst` for why both are needed for a screen whose state transitions ride on real
 * emulator network I/O and Firestore-listener-backed flows.
 *
 * The Google sign-in button (`signInWithGoogle`) needs a real Activity + Credential Manager and
 * isn't exercised here beyond confirming it renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var session: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = SessionViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed-out state shows both sign-in options`() {
        composeRule.setContent { WelcomeScreen(session) }

        composeRule.onNodeWithText("Sign in with Google").assertExists()
        composeRule.onNodeWithText("Continue without Google", substring = true).assertExists()
    }

    @Test
    fun `continue without Google moves to the CHOOSE step`(): Unit = runBlocking {
        withTimeout(5000) {
            composeRule.setContent { WelcomeScreen(session) }

            composeRule.onNodeWithText("Continue without Google", substring = true).performClick()
            session.isSignedIn.awaitFirst { it }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Set up a new dog").assertExists()
            composeRule.onNodeWithText("Join with a household code").assertExists()
        }
    }

    @Test
    fun `create-household path reaches Ready`() = runBlocking {
        withTimeout(5000) {
            composeRule.setContent { WelcomeScreen(session) }

            composeRule.onNodeWithText("Continue without Google", substring = true).performClick()
            session.isSignedIn.awaitFirst { it }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Set up a new dog").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Dog's name").performTextInput("Rex")
            composeRule.onNodeWithText("Your name").performTextInput("Tom")
            composeRule.onNodeWithText("Create").performClick()

            val state = session.state.awaitFirst { it is SessionState.Ready } as SessionState.Ready
            assertEquals("Tom", state.displayName)
            assertEquals(true, state.householdId.isNotBlank())
        }
    }

    @Test
    fun `join-household path with a bad code shows the error instead of transitioning`() = runBlocking {
        withTimeout(5000) {
            composeRule.setContent { WelcomeScreen(session) }

            composeRule.onNodeWithText("Continue without Google", substring = true).performClick()
            session.isSignedIn.awaitFirst { it }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Join with a household code").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Household code").performTextInput("ZZZZZZ")
            composeRule.onNodeWithText("Your name").performTextInput("Alice")
            composeRule.onNodeWithText("Join").performClick()

            val error = session.error.awaitFirst { it != null }
            assertNotNull(error)
            composeRule.waitForIdle()

            composeRule.onNodeWithText(error!!).assertExists()
            assertEquals(false, session.state.value is SessionState.Ready)
        }
    }
}
