package com.atnip.seizuretracker.ui.entry

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives [QuickAddSheet] in isolation — no Firestore/emulator dependency, since this composable
 * is pure UI over the callbacks it's given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAddSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows both options personalized with the pet's name`() {
        composeRule.setContent {
            QuickAddSheet(petName = "Bear", onSeizure = {}, onHealthNote = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("New entry for Bear").assertExists()
        composeRule.onNodeWithText("Seizure").assertExists()
        composeRule.onNodeWithText("Same fast form as always").assertExists()
        composeRule.onNodeWithText("Other / health note").assertExists()
        composeRule.onNodeWithText("Anything else worth mentioning").assertExists()
    }

    @Test
    fun `tapping Seizure invokes onSeizure and dismisses, not onHealthNote`() {
        var seizureCalled = false
        var healthNoteCalled = false
        var dismissed = false
        composeRule.setContent {
            QuickAddSheet(
                petName = "Bear",
                onSeizure = { seizureCalled = true },
                onHealthNote = { healthNoteCalled = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Seizure").performClick()

        assertEquals(true, seizureCalled)
        assertEquals(false, healthNoteCalled)
        assertEquals(true, dismissed)
    }

    @Test
    fun `tapping Other + health note invokes onHealthNote and dismisses, not onSeizure`() {
        var seizureCalled = false
        var healthNoteCalled = false
        var dismissed = false
        composeRule.setContent {
            QuickAddSheet(
                petName = "Bear",
                onSeizure = { seizureCalled = true },
                onHealthNote = { healthNoteCalled = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("Other / health note").performClick()

        assertEquals(false, seizureCalled)
        assertEquals(true, healthNoteCalled)
        assertEquals(true, dismissed)
    }
}
