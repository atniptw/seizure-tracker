package com.atnip.seizuretracker.data.repository

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.PetSpecies
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [PetRepository] object against the Firebase Local Emulator Suite — see
 * [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class PetRepositoryTest {

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
    fun `add, update, delete, and getPetOnce round-trip`() = runBlocking {
        withTimeout(10000) {
            val pet = Pet(name = "Bear", species = PetSpecies.ALL[0], breed = "Lab")
            val petId = PetRepository.addPet(householdId, pet)
            assertTrue(petId.isNotBlank())

            val fetched = PetRepository.getPetOnce(householdId, petId)
            assertEquals("Bear", fetched?.name)

            PetRepository.updatePet(householdId, fetched!!.copy(name = "Bear Jr."))
            val fetchedAfterUpdate = PetRepository.getPetOnce(householdId, petId)
            assertEquals("Bear Jr.", fetchedAfterUpdate?.name)

            PetRepository.deletePet(householdId, petId)
            assertNull(PetRepository.getPetOnce(householdId, petId))
        }
    }

    @Test
    fun `observePets orders ascending by createdAtMillis`() = runBlocking {
        withTimeout(10000) {
            PetRepository.addPet(householdId, Pet(name = "Milo", createdAtMillis = 200L))
            PetRepository.addPet(householdId, Pet(name = "Bear", createdAtMillis = 100L))

            val list = PetRepository.observePets(householdId).awaitFirst { it.size == 2 }
            assertEquals(listOf("Bear", "Milo"), list.map { it.name })
        }
    }
}
