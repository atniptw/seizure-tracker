@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.household

import android.os.Looper
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the real [HouseholdViewModel] (and, transitively, [HouseholdRepository] and
 * [com.atnip.seizuretracker.data.repository.MemberRepository]) against the Firebase Local
 * Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class HouseholdViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String
    private lateinit var ownerUid: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            withTimeout(10000) {
                ownerUid = AuthRepository.signInAnonymously()
                householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `household StateFlow eventually reflects the seeded household`() = runBlocking {
        withTimeout(10000) {
            val viewModel = HouseholdViewModel(householdId)

            val household = viewModel.household.awaitFirst { it != null }
            assertEquals("Rex", household?.name)
            assertEquals(householdId, household?.id)
        }
    }

    @Test
    fun `updateHouseholdName renames the household`() = runBlocking {
        withTimeout(10000) {
            val viewModel = HouseholdViewModel(householdId)
            viewModel.household.awaitFirst { it != null }

            viewModel.updateHouseholdName("The Bear house")

            val updated = viewModel.household.awaitFirst { it?.name == "The Bear house" }
            assertEquals("The Bear house", updated?.name)
        }
    }

    @Test
    fun `members StateFlow reflects the creator's own profile`() = runBlocking {
        withTimeout(10000) {
            val viewModel = HouseholdViewModel(householdId)

            val members = viewModel.members.awaitFirst { it.isNotEmpty() }
            assertEquals(1, members.size)
            assertEquals("Alex", members[0].displayName)
            assertEquals(ownerUid, members[0].uid)
        }
    }

    @Test
    fun `removeMember drops a joined member from the members StateFlow`() = runBlocking {
        withTimeout(10000) {
            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            // See HouseholdRepositoryTest for why this idle() is needed after switching identity.
            shadowOf(Looper.getMainLooper()).idle()
            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)

            val viewModel = HouseholdViewModel(householdId)
            viewModel.members.awaitFirst { it.size == 2 }

            viewModel.removeMember(joinerUid)

            val members = viewModel.members.awaitFirst { it.size == 1 }
            assertTrue(members.none { it.uid == joinerUid })

            // Verified via a fresh repository read rather than `viewModel.household`: this is a
            // self-removal (joiner removes themselves), which revokes the caller's own read
            // access mid-write. An *already-listening* StateFlow subscription established before
            // that revocation doesn't reliably receive the final snapshot from the emulator
            // (unlike a freshly-started listener, which does — see the equivalent assertion in
            // HouseholdRepositoryTest). The app's UI never actually hits this edge case, since
            // self-removal is blocked there; this just confirms the write landed.
            val household = HouseholdRepository.observeHousehold(householdId).awaitFirst { it?.members?.size == 1 }
            assertEquals(listOf(ownerUid), household?.members)
        }
    }
}
