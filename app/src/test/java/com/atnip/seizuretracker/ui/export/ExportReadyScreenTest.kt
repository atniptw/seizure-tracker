@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.atnip.seizuretracker.ui.export

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Seizure
import com.atnip.seizuretracker.testutil.awaitFirst
import com.atnip.seizuretracker.testutil.resetFileProviderCache
import com.atnip.seizuretracker.ui.common.Entry
import com.atnip.seizuretracker.ui.navigation.Destinations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives [ExportReadyScreen] against a real [ExportViewModel] — no Firestore/emulator involved,
 * since export generation is local file I/O. Uses CSV format throughout: see the note in
 * [ExportViewModelTest] about Robolectric's `PdfDocument` shadow leaking "closed" state globally
 * across test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportReadyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetFileProviderCache()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setScreenContent(exportViewModel: ExportViewModel) {
        composeRule.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = Destinations.EXPORT_READY) {
                composable(Destinations.DASHBOARD) {}
                composable(Destinations.EXPORT_READY) {
                    ExportReadyScreen(navController = navController, exportViewModel = exportViewModel)
                }
            }
        }
    }

    @Test
    fun `shows a preparing message while no result is set yet`() {
        setScreenContent(ExportViewModel())

        composeRule.onNodeWithText("Preparing your export…").assertExists()
    }

    @Test
    fun `shows the entry count, pet names, and file name once a result is set`(): Unit = runBlocking {
        withTimeout(5000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(format = ExportFormat.CSV) }
            val bear = Pet(id = "p1", name = "Bear")
            viewModel.generate(
                context,
                listOf(bear),
                listOf(
                    Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1_000L)),
                    Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 2_000L)),
                    Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 3_000L))
                ),
                emptyList(),
                emptyList()
            )
            viewModel.result.awaitFirst { it != null }

            setScreenContent(viewModel)

            composeRule.onNodeWithText("3 entries for Bear").assertExists()
            composeRule.onNodeWithText("Share").assertExists()
            composeRule.onNodeWithText("Save to device").assertExists()
        }
    }

    @Test
    fun `singular entry count reads entry not entries`(): Unit = runBlocking {
        withTimeout(5000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(format = ExportFormat.CSV) }
            val bear = Pet(id = "p1", name = "Bear")
            viewModel.generate(context, listOf(bear), listOf(Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1_000L))), emptyList(), emptyList())
            viewModel.result.awaitFirst { it != null }

            setScreenContent(viewModel)

            composeRule.onNodeWithText("1 entry for Bear").assertExists()
        }
    }

    @Test
    fun `tapping Done clears the result and returns to the dashboard`() = runBlocking {
        withTimeout(5000) {
            val viewModel = ExportViewModel()
            viewModel.updateOptions { it.copy(format = ExportFormat.CSV) }
            val bear = Pet(id = "p1", name = "Bear")
            viewModel.generate(context, listOf(bear), listOf(Entry.SeizureEntry(Seizure(petId = "p1", timestampMillis = 1_000L))), emptyList(), emptyList())
            viewModel.result.awaitFirst { it != null }

            setScreenContent(viewModel)

            composeRule.onNodeWithText("Done").performClick()
            composeRule.waitForIdle()

            org.junit.Assert.assertTrue(viewModel.result.value == null)
        }
    }
}
