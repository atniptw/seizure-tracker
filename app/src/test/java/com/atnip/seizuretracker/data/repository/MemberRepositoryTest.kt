package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [MemberRepository] object against the Firebase Local Emulator Suite — see
 * [FirebaseEmulatorRule]. [HouseholdRepository]'s `createHousehold`/`joinHousehold`/`removeMember`
 * already cover the realistic call sites of this repository; these tests cover its own API
 * directly.
 */
@RunWith(RobolectricTestRunner::class)
class MemberRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String
    private lateinit var uid: String

    @Before
    fun seedHousehold() = runBlocking {
        withTimeout(10000) {
            uid = AuthRepository.signInAnonymously()
            householdId = HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
        }
    }

    @Test
    fun `upsertOwnProfile overwrites a previously written profile for the same uid`() = runBlocking {
        withTimeout(10000) {
            // createHousehold already wrote a profile for uid with displayName "Alex"; overwrite it.
            MemberRepository.upsertOwnProfile(householdId, uid, "Alexandra", AuthMethods.ANONYMOUS)

            val members = MemberRepository.observeMembers(householdId).awaitFirst { it.firstOrNull()?.displayName == "Alexandra" }
            assertEquals(1, members.size)
            assertEquals("Alexandra", members[0].displayName)
        }
    }

    @Test
    fun `deleteMemberProfile removes just that uid's profile`() = runBlocking {
        withTimeout(10000) {
            MemberRepository.deleteMemberProfile(householdId, uid)
            val members = MemberRepository.observeMembers(householdId).awaitFirst { true }
            assertTrue(members.isEmpty())
        }
    }
}
