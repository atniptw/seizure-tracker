package com.atnip.seizuretracker.ui.household

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
 * Drives [RemoveMemberDialog] in isolation — no Firestore/emulator dependency, since this
 * composable is pure UI over the callbacks it's given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoveMemberDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows the member's name in the title and explanation`() {
        composeRule.setContent {
            RemoveMemberDialog(memberName = "Jamie", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Remove Jamie from this household?").assertExists()
        composeRule.onNodeWithText(
            "Jamie will lose access on their device immediately. Entries they've already logged stay in the history."
        ).assertExists()
    }

    @Test
    fun `tapping Remove invokes onConfirm, not onDismiss`() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            RemoveMemberDialog(memberName = "Jamie", onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }

        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(true, confirmed)
        assertEquals(false, dismissed)
    }

    @Test
    fun `tapping Cancel invokes onDismiss, not onConfirm`() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            RemoveMemberDialog(memberName = "Jamie", onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(false, confirmed)
        assertEquals(true, dismissed)
    }
}
