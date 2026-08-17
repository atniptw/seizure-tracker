package com.atnip.seizuretracker.ui.vet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.data.model.Vet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives [LinkVetSheet] in isolation with plain in-memory [Vet]/[PetVetLink] fixtures — no
 * Firestore/emulator dependency, since this composable is pure UI over the list/callbacks it's
 * given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkVetSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val riverside = Vet(id = "riverside", name = "Riverside Animal Hospital")
    private val oakview = Vet(id = "oakview", name = "Oakview Emergency Vet")

    @Test
    fun `lists vets not already linked to this pet, and hides an already-linked one`() {
        val existingLink = PetVetLink(id = "l1", petId = "bear", vetId = "riverside", role = "General")
        composeRule.setContent {
            LinkVetSheet(
                petName = "Bear",
                vets = listOf(riverside, oakview),
                linksForPet = listOf(existingLink),
                onLinkExisting = { _, _ -> },
                onAddNew = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("Link a vet for Bear").assertExists()
        composeRule.onNodeWithText("Oakview Emergency Vet").assertExists()
        composeRule.onNodeWithText("Riverside Animal Hospital").assertDoesNotExist()
    }

    @Test
    fun `selecting a vet reveals the role picker defaulted to General, and Link vet confirms it`() {
        var linkedVetId: String? = null
        var linkedRole: String? = null
        var dismissed = false
        composeRule.setContent {
            LinkVetSheet(
                petName = "Bear",
                vets = listOf(riverside),
                linksForPet = emptyList(),
                onLinkExisting = { vetId, role -> linkedVetId = vetId; linkedRole = role },
                onAddNew = {},
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Riverside Animal Hospital").performClick()
        composeRule.onNodeWithText("Role for Bear").assertExists()

        composeRule.onNodeWithText("Link vet").performClick()

        assertEquals("riverside", linkedVetId)
        assertEquals("General", linkedRole)
        assertEquals(true, dismissed)
    }

    @Test
    fun `Link vet is disabled until a vet is selected`() {
        var linked = false
        composeRule.setContent {
            LinkVetSheet(
                petName = "Bear",
                vets = listOf(riverside),
                linksForPet = emptyList(),
                onLinkExisting = { _, _ -> linked = true },
                onAddNew = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("Link vet").performClick()

        assertEquals(false, linked)
    }

    @Test
    fun `tapping Add a new vet invokes onAddNew and dismisses`() {
        var addNewCalled = false
        var dismissed = false
        composeRule.setContent {
            LinkVetSheet(
                petName = "Bear",
                vets = listOf(riverside),
                linksForPet = emptyList(),
                onLinkExisting = { _, _ -> },
                onAddNew = { addNewCalled = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Add a new vet").performClick()

        assertEquals(true, addNewCalled)
        assertEquals(true, dismissed)
    }
}
