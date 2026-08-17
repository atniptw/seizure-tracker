@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.session

import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [SessionViewModel] — including its real DataStore-backed local prefs
 * (needs a real Context, supplied by Robolectric) and real repositories — against the Firebase
 * Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main.immediate, which isn't registered on a
        // plain JVM/Robolectric by default. Unconfined (rather than Standard) so launched
        // coroutines run without needing an explicit scheduler pump, since these tests await
        // real emulator network I/O rather than virtual time.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SessionViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createHousehold signs in, creates a household, and lands on Ready`() = runBlocking {
        withTimeout(10000) {
            val uid = AuthRepository.signInAnonymously()

            viewModel.createHousehold("Rex", "Tom")

            val state = viewModel.state.first { it is SessionState.Ready } as SessionState.Ready
            assertEquals(uid, state.uid)
            assertEquals("Tom", state.displayName)
            assertTrue(state.householdId.isNotBlank())
        }
    }

    @Test
    fun `joinHousehold with a valid code lands on Ready`() = runBlocking {
        withTimeout(10000) {
            val creatorUid = AuthRepository.signInAnonymously()
            val fixtureHouseholdId = HouseholdRepository.createHousehold("Rex", creatorUid, "Alex", AuthMethods.ANONYMOUS)
            val code = HouseholdRepository.observeHousehold(fixtureHouseholdId).awaitFirst { it != null }!!.code

            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()

            viewModel.joinHousehold(code, "Alice")

            val state = viewModel.state.first { it is SessionState.Ready } as SessionState.Ready
            assertEquals(fixtureHouseholdId, state.householdId)
            assertEquals(joinerUid, state.uid)
            assertEquals("Alice", state.displayName)
        }
    }

    @Test
    fun `joinHousehold with a bad code sets error and does not transition to Ready`() = runBlocking {
        withTimeout(10000) {
            AuthRepository.signInAnonymously()

            viewModel.joinHousehold("ZZZZZZ", "Alice")

            val error = viewModel.error.first { it != null }
            assertNotNull(error)
            assertTrue(viewModel.state.value !is SessionState.Ready)
        }
    }
}
