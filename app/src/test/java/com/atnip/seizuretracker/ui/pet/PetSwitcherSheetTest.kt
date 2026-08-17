package com.atnip.seizuretracker.ui.pet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atnip.seizuretracker.data.model.Pet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * Drives [PetSwitcherSheet] in isolation with plain in-memory [Pet] fixtures — no Firestore/
 * emulator dependency, since this composable is pure UI over the list/callbacks it's given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetSwitcherSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bear = Pet(id = "bear", name = "Bear", species = "Dog")
    private val milo = Pet(id = "milo", name = "Milo", species = "Cat")

    @Test
    fun `lists every pet and marks the active one`() {
        composeRule.setContent {
            PetSwitcherSheet(
                pets = listOf(bear, milo),
                activePetId = "bear",
                onSelect = {},
                onAddPet = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("Switch pet").assertExists()
        composeRule.onNodeWithText("Bear").assertExists()
        composeRule.onNodeWithText("Milo").assertExists()
        composeRule.onNodeWithText("Add a pet").assertExists()
    }

    @Test
    fun `tapping a pet invokes onSelect with that pet and dismisses`() {
        var selected: Pet? = null
        var dismissed = false
        composeRule.setContent {
            PetSwitcherSheet(
                pets = listOf(bear, milo),
                activePetId = "bear",
                onSelect = { selected = it },
                onAddPet = {},
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Milo").performClick()

        assertEquals(milo, selected)
        assertEquals(true, dismissed)
    }

    @Test
    fun `tapping Add a pet invokes onAddPet and dismisses`() {
        var addPetCalled = false
        var dismissed = false
        composeRule.setContent {
            PetSwitcherSheet(
                pets = listOf(bear),
                activePetId = "bear",
                onSelect = {},
                onAddPet = { addPetCalled = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Add a pet").performClick()

        assertEquals(true, addPetCalled)
        assertEquals(true, dismissed)
    }
}
