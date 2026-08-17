package com.atnip.seizuretracker.data.repository

import android.os.Looper
import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the real [HouseholdRepository] object against the Firebase Local Emulator Suite —
 * see [FirebaseEmulatorRule]. No mocks: this is the only way to get real confidence out of a
 * Kotlin `object` singleton wired directly to `FirebaseFirestore.getInstance()`.
 */
@RunWith(RobolectricTestRunner::class)
class HouseholdRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private suspend fun firstHousehold(householdId: String): Household =
        HouseholdRepository.observeHousehold(householdId).awaitFirst { it != null }!!

    @Test
    fun `createHousehold produces a unique code, a matching codeIndex doc, and the creator as sole member`() =
        runBlocking {
            withTimeout(10000) {
                val ownerUid = AuthRepository.signInAnonymously()

                val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)
                val household = firstHousehold(householdId)

                assertEquals("Rex", household.name)
                assertEquals(listOf(ownerUid), household.members)
                assertEquals(6, household.code.length)

                val resolvedId = HouseholdRepository.findHouseholdIdByCode(household.code)
                assertEquals(householdId, resolvedId)
            }
        }

    @Test
    fun `findHouseholdIdByCode round-trips and normalizes case and whitespace`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)
            val code = firstHousehold(householdId).code

            assertEquals(householdId, HouseholdRepository.findHouseholdIdByCode(code))
            assertEquals(
                householdId,
                HouseholdRepository.findHouseholdIdByCode("  ${code.lowercase()}  ")
            )
            assertNull(HouseholdRepository.findHouseholdIdByCode("NOTREAL"))
        }
    }

    @Test
    fun `joinHousehold is idempotent on double-join`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            // Simulate a second device: sign out of the anonymous session that created the
            // household and sign in fresh, which mints a brand-new anonymous uid.
            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            // Firestore's credential provider picks up a freshly-signed-in identity slightly
            // asynchronously relative to FirebaseAuth.currentUser itself; idle the looper so the
            // new uid is actually attached to the next outgoing Firestore request instead of a
            // stale one racing ahead of it.
            shadowOf(Looper.getMainLooper()).idle()
            assertNotEquals(ownerUid, joinerUid)

            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)
            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)

            val household = firstHousehold(householdId)
            assertEquals(2, household.members.size)
            assertTrue(household.members.containsAll(listOf(ownerUid, joinerUid)))
        }
    }

    @Test
    fun `observeHousehold emits live updates`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            val values = mutableListOf<Household?>()
            val collectJob = launch {
                HouseholdRepository.observeHousehold(householdId).collect { values.add(it) }
            }

            while (values.size < 1) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            assertEquals("Rex", values[0]?.name)

            HouseholdRepository.updateHouseholdName(householdId, "Max")

            while (values.size < 2) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            assertEquals("Max", values[1]?.name)

            collectJob.cancel()
        }
    }

    @Test
    fun `createHousehold writes the creator's own member profile`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            val members = MemberRepository.observeMembers(householdId).awaitFirst { it.isNotEmpty() }
            assertEquals(1, members.size)
            assertEquals("Alex", members[0].displayName)
            assertEquals(AuthMethods.ANONYMOUS, members[0].authMethod)
            assertEquals(ownerUid, members[0].uid)
        }
    }

    @Test
    fun `joinHousehold writes the joiner's own member profile`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            // Firestore's credential provider picks up a freshly-signed-in identity slightly
            // asynchronously relative to FirebaseAuth.currentUser itself; idle the looper so the
            // new uid is actually attached to the next outgoing Firestore request instead of a
            // stale one racing ahead of it.
            shadowOf(Looper.getMainLooper()).idle()
            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)

            val members = MemberRepository.observeMembers(householdId).awaitFirst { it.size == 2 }
            val sam = members.first { it.uid == joinerUid }
            assertEquals("Sam", sam.displayName)
        }
    }

    @Test
    fun `updateHouseholdName renames the household`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            HouseholdRepository.updateHouseholdName(householdId, "The Bear & Milo house")

            val household = HouseholdRepository.observeHousehold(householdId)
                .awaitFirst { it?.name == "The Bear & Milo house" }
            assertEquals("The Bear & Milo house", household?.name)
        }
    }

    @Test
    fun `removeMember drops the uid from members and deletes their profile`() = runBlocking {
        withTimeout(10000) {
            val ownerUid = AuthRepository.signInAnonymously()
            val householdId = HouseholdRepository.createHousehold("Rex", ownerUid, "Alex", AuthMethods.ANONYMOUS)

            FirebaseAuth.getInstance().signOut()
            val joinerUid = AuthRepository.signInAnonymously()
            // Firestore's credential provider picks up a freshly-signed-in identity slightly
            // asynchronously relative to FirebaseAuth.currentUser itself; idle the looper so the
            // new uid is actually attached to the next outgoing Firestore request instead of a
            // stale one racing ahead of it.
            shadowOf(Looper.getMainLooper()).idle()
            HouseholdRepository.joinHousehold(householdId, joinerUid, "Sam", AuthMethods.ANONYMOUS)

            // Joiner removes themselves here (still signed in as joiner) — rules permit any
            // current member to remove any uid including their own; the app's UI is what
            // prevents self-removal, not the rules.
            HouseholdRepository.removeMember(householdId, joinerUid)

            val household = HouseholdRepository.observeHousehold(householdId)
                .awaitFirst { it?.members?.size == 1 }
            assertEquals(listOf(ownerUid), household?.members)

            val members = MemberRepository.observeMembers(householdId).awaitFirst { it.size == 1 }
            assertTrue(members.none { it.uid == joinerUid })
        }
    }
}
