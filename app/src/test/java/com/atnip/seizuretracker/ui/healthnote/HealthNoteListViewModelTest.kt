@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.healthnote

import com.atnip.seizuretracker.data.model.AuthMethods
import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.repository.AuthRepository
import com.atnip.seizuretracker.data.repository.HouseholdRepository
import com.atnip.seizuretracker.testutil.FirebaseEmulatorRule
import com.atnip.seizuretracker.testutil.awaitFirst
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

/**
 * Exercises the real [HealthNoteListViewModel] (and, transitively, [HealthNoteRepository])
 * against the Firebase Local Emulator Suite. See [FirebaseEmulatorRule].
 */
@RunWith(RobolectricTestRunner::class)
class HealthNoteListViewModelTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var householdId: String
    private lateinit var viewModel: HealthNoteListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        householdId = runBlocking {
            withTimeout(5000) {
                val uid = AuthRepository.signInAnonymously()
                HouseholdRepository.createHousehold("The Bear house", uid, "Alex", AuthMethods.ANONYMOUS)
            }
        }
        viewModel = HealthNoteListViewModel(householdId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addHealthNote, updateHealthNote, and deleteHealthNote round-trip through the ViewModel`() = runBlocking {
        withTimeout(5000) {
            viewModel.addHealthNote(HealthNote(petId = "bear", description = "Limping")) {}

            val notes = viewModel.healthNotes.awaitFirst { it.isNotEmpty() }
            assertEquals("Limping", notes[0].description)
            assertTrue(notes[0].flaggedForVet)
            val added = notes[0]

            viewModel.updateHealthNote(added.copy(description = "Limping less"))
            val updated = viewModel.healthNotes.awaitFirst { it.firstOrNull()?.description == "Limping less" }
            assertEquals("Limping less", updated[0].description)

            viewModel.deleteHealthNote(added.id)
            val afterDelete = viewModel.healthNotes.awaitFirst { it.isEmpty() }
            assertTrue(afterDelete.isEmpty())
        }
    }

    @Test
    fun `healthNotes orders descending by timestampMillis`() = runBlocking {
        withTimeout(5000) {
            viewModel.addHealthNote(HealthNote(petId = "bear", timestampMillis = 100L, description = "oldest"))
            viewModel.addHealthNote(HealthNote(petId = "bear", timestampMillis = 300L, description = "newest"))
            viewModel.addHealthNote(HealthNote(petId = "bear", timestampMillis = 200L, description = "middle"))

            val notes = viewModel.healthNotes.awaitFirst { it.size == 3 }
            assertEquals(listOf("newest", "middle", "oldest"), notes.map { it.description })
        }
    }
}
