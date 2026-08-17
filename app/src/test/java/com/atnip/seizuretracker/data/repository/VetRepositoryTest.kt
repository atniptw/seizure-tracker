package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Vet
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
 * Exercises the real [VetRepository] object against the Firebase Local Emulator Suite — see
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class VetRepositoryTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String

    @Before
    fun seedHousehold() = runBlocking {
        withTimeout(10000) {
            val uid = AuthRepository.signInAnonymously()
            householdId = HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
        }
    }

    @Test
    fun `add and update round-trip`() = runBlocking {
        withTimeout(10000) {
            val vetId = VetRepository.addVet(householdId, Vet(name = "Riverside Animal Hospital", phone = "555-0192"))
            assertTrue(vetId.isNotBlank())

            var list = VetRepository.observeVets(householdId).awaitFirst { it.isNotEmpty() }
            assertEquals("555-0192", list[0].phone)

            VetRepository.updateVet(householdId, list[0].copy(phone = "555-0000"))
            list = VetRepository.observeVets(householdId).awaitFirst { it.firstOrNull()?.phone == "555-0000" }
            assertEquals("555-0000", list[0].phone)
        }
    }

    @Test
    fun `deleteVet also removes its pet-vet links but leaves other links alone`() = runBlocking {
        withTimeout(10000) {
            val vetId = VetRepository.addVet(householdId, Vet(name = "Riverside Animal Hospital"))
            val otherVetId = VetRepository.addVet(householdId, Vet(name = "Oakview Emergency Vet"))
            PetVetLinkRepository.addLink(householdId, petId = "bear", vetId = vetId, role = "General")
            PetVetLinkRepository.addLink(householdId, petId = "milo", vetId = vetId, role = "General")
            PetVetLinkRepository.addLink(householdId, petId = "bear", vetId = otherVetId, role = "Emergency")

            VetRepository.deleteVet(householdId, vetId)

            val vets = VetRepository.observeVets(householdId).awaitFirst { it.size == 1 }
            assertEquals(otherVetId, vets[0].id)

            val links = PetVetLinkRepository.observeLinks(householdId).awaitFirst { it.size == 1 }
            assertEquals(otherVetId, links[0].vetId)
        }
    }
}
