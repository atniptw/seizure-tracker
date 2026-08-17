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
 * Exercises the real [PetVetLinkRepository] object against the Firebase Local Emulator Suite —
 * see [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class PetVetLinkRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun seedHousehold() = runBlocking {
        withTimeout(5000) {
            val uid = AuthRepository.signInAnonymously()
            householdId = HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
        }
    }

    @Test
    fun `add, updateLinkRole, and removeLink round-trip`() = runBlocking {
        withTimeout(5000) {
            val linkId = PetVetLinkRepository.addLink(householdId, petId = "bear", vetId = "riverside", role = "General")
            assertTrue(linkId.isNotBlank())

            var links = PetVetLinkRepository.observeLinks(householdId).awaitFirst { it.isNotEmpty() }
            assertEquals("General", links[0].role)

            PetVetLinkRepository.updateLinkRole(householdId, linkId, "Emergency")
            links = PetVetLinkRepository.observeLinks(householdId).awaitFirst { it.firstOrNull()?.role == "Emergency" }
            assertEquals("Emergency", links[0].role)

            PetVetLinkRepository.removeLink(householdId, linkId)
            links = PetVetLinkRepository.observeLinks(householdId).awaitFirst { true }
            assertTrue(links.isEmpty())
        }
    }

    @Test
    fun `a pet can have multiple vets and a vet can serve multiple pets`() = runBlocking {
        withTimeout(5000) {
            PetVetLinkRepository.addLink(householdId, petId = "bear", vetId = "riverside", role = "General")
            PetVetLinkRepository.addLink(householdId, petId = "bear", vetId = "oakview", role = "Emergency")
            PetVetLinkRepository.addLink(householdId, petId = "milo", vetId = "riverside", role = "General")

            val links = PetVetLinkRepository.observeLinks(householdId).awaitFirst { it.size == 3 }
            assertEquals(2, links.count { it.petId == "bear" })
            assertEquals(2, links.count { it.vetId == "riverside" })
        }
    }
}
