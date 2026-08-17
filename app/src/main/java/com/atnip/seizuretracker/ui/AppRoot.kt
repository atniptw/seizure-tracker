package com.atnip.seizuretracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atnip.seizuretracker.ui.accessibility.AccessibilityScreen
import com.atnip.seizuretracker.ui.accessibility.AccessibilityViewModel
import com.atnip.seizuretracker.ui.dashboard.DashboardScreen
import com.atnip.seizuretracker.ui.entry.EntryHistoryScreen
import com.atnip.seizuretracker.ui.export.ExportReadyScreen
import com.atnip.seizuretracker.ui.export.ExportScreen
import com.atnip.seizuretracker.ui.export.ExportViewModel
import com.atnip.seizuretracker.ui.healthnote.AddEditHealthNoteScreen
import com.atnip.seizuretracker.ui.healthnote.HealthNoteListViewModel
import com.atnip.seizuretracker.ui.household.HouseholdScreen
import com.atnip.seizuretracker.ui.household.HouseholdViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.pet.AddEditPetScreen
import com.atnip.seizuretracker.ui.pet.ManagePetsScreen
import com.atnip.seizuretracker.ui.pet.PetListViewModel
import com.atnip.seizuretracker.ui.seizure.AddEditSeizureScreen
import com.atnip.seizuretracker.ui.seizure.SeizureDetailScreen
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.ui.session.SessionState
import com.atnip.seizuretracker.ui.session.SessionViewModel
import com.atnip.seizuretracker.ui.settings.SettingsHubScreen
import com.atnip.seizuretracker.ui.vet.VetDetailScreen
import com.atnip.seizuretracker.ui.vet.VetViewModel
import com.atnip.seizuretracker.ui.vet.VetsDirectoryScreen
import com.atnip.seizuretracker.ui.welcome.WelcomeScreen

@Composable
fun AppRoot(session: SessionViewModel, accessibilityViewModel: AccessibilityViewModel) {
    val sessionState by session.state.collectAsState()

    when (val s = sessionState) {
        is SessionState.Loading -> LoadingScreen()
        is SessionState.NeedsSetup -> WelcomeScreen(session)
        is SessionState.Ready -> {
            val context = LocalContext.current
            // Keys must be unique per ViewModel *type*, not just per household — two
            // viewModel() calls sharing a key collide in the same ViewModelStore, and the
            // second call's put() clears whatever the first one stored under that key.
            val seizureListViewModel: SeizureListViewModel = viewModel(
                key = "seizureList_${s.householdId}",
                factory = SeizureListViewModel.factory(s.householdId)
            )
            val petListViewModel: PetListViewModel = viewModel(
                key = "petList_${s.householdId}",
                factory = PetListViewModel.factory(context, s.householdId)
            )
            val healthNoteListViewModel: HealthNoteListViewModel = viewModel(
                key = "healthNoteList_${s.householdId}",
                factory = HealthNoteListViewModel.factory(s.householdId)
            )
            val vetViewModel: VetViewModel = viewModel(
                key = "vet_${s.householdId}",
                factory = VetViewModel.factory(s.householdId)
            )
            val householdViewModel: HouseholdViewModel = viewModel(
                key = "household_${s.householdId}",
                factory = HouseholdViewModel.factory(s.householdId)
            )
            val exportViewModel: ExportViewModel = viewModel(
                key = "export_${s.householdId}",
                factory = ExportViewModel.factory()
            )
            MainNavHost(
                displayName = s.displayName,
                uid = s.uid,
                session = session,
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel,
                vetViewModel = vetViewModel,
                householdViewModel = householdViewModel,
                exportViewModel = exportViewModel,
                accessibilityViewModel = accessibilityViewModel
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MainNavHost(
    displayName: String,
    uid: String,
    session: SessionViewModel,
    seizureListViewModel: SeizureListViewModel,
    healthNoteListViewModel: HealthNoteListViewModel,
    petListViewModel: PetListViewModel,
    vetViewModel: VetViewModel,
    householdViewModel: HouseholdViewModel,
    exportViewModel: ExportViewModel,
    accessibilityViewModel: AccessibilityViewModel
) {
    val navController = rememberNavController()
    val activePet by petListViewModel.activePet.collectAsState()

    NavHost(navController = navController, startDestination = Destinations.DASHBOARD) {
        composable(Destinations.DASHBOARD) {
            DashboardScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                petListViewModel = petListViewModel
            )
        }
        composable(Destinations.ENTRY_HISTORY) {
            EntryHistoryScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                activePetId = activePet?.id ?: ""
            )
        }
        composable(Destinations.ADD_SEIZURE) {
            AddEditSeizureScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                displayName = displayName,
                uid = uid,
                activePetId = activePet?.id ?: "",
                existingSeizureId = null
            )
        }
        composable(
            Destinations.EDIT_SEIZURE,
            arguments = listOf(navArgument("seizureId") { type = NavType.StringType })
        ) { backStackEntry ->
            val seizureId = backStackEntry.arguments?.getString("seizureId")
            AddEditSeizureScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                displayName = displayName,
                uid = uid,
                activePetId = activePet?.id ?: "",
                existingSeizureId = seizureId
            )
        }
        composable(
            Destinations.SEIZURE_DETAIL,
            arguments = listOf(navArgument("seizureId") { type = NavType.StringType })
        ) { backStackEntry ->
            val seizureId = backStackEntry.arguments?.getString("seizureId") ?: ""
            SeizureDetailScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                seizureId = seizureId
            )
        }
        composable(
            Destinations.ADD_HEALTH_NOTE,
            arguments = listOf(navArgument("petId") { type = NavType.StringType })
        ) { backStackEntry ->
            val petId = backStackEntry.arguments?.getString("petId") ?: (activePet?.id ?: "")
            AddEditHealthNoteScreen(
                navController = navController,
                healthNoteListViewModel = healthNoteListViewModel,
                displayName = displayName,
                uid = uid,
                activePetId = petId,
                existingNoteId = null
            )
        }
        composable(
            Destinations.EDIT_HEALTH_NOTE,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")
            AddEditHealthNoteScreen(
                navController = navController,
                healthNoteListViewModel = healthNoteListViewModel,
                displayName = displayName,
                uid = uid,
                activePetId = activePet?.id ?: "",
                existingNoteId = noteId
            )
        }
        composable(Destinations.EXPORT) {
            ExportScreen(
                navController = navController,
                petListViewModel = petListViewModel,
                seizureListViewModel = seizureListViewModel,
                healthNoteListViewModel = healthNoteListViewModel,
                vetViewModel = vetViewModel,
                exportViewModel = exportViewModel
            )
        }
        composable(Destinations.EXPORT_READY) {
            ExportReadyScreen(navController = navController, exportViewModel = exportViewModel)
        }
        composable(Destinations.ACCESSIBILITY) {
            AccessibilityScreen(navController = navController, viewModel = accessibilityViewModel)
        }
        composable(Destinations.MANAGE_PETS) {
            ManagePetsScreen(navController = navController, petListViewModel = petListViewModel)
        }
        composable(Destinations.ADD_PET) {
            AddEditPetScreen(
                navController = navController,
                petListViewModel = petListViewModel,
                vetViewModel = vetViewModel,
                existingPetId = null
            )
        }
        composable(
            Destinations.EDIT_PET,
            arguments = listOf(navArgument("petId") { type = NavType.StringType })
        ) { backStackEntry ->
            val petId = backStackEntry.arguments?.getString("petId")
            AddEditPetScreen(
                navController = navController,
                petListViewModel = petListViewModel,
                vetViewModel = vetViewModel,
                existingPetId = petId
            )
        }
        composable(Destinations.VETS) {
            VetsDirectoryScreen(navController = navController, vetViewModel = vetViewModel, petListViewModel = petListViewModel)
        }
        composable(
            Destinations.VET_DETAIL,
            arguments = listOf(navArgument("vetId") { type = NavType.StringType })
        ) { backStackEntry ->
            val vetId = backStackEntry.arguments?.getString("vetId")
            VetDetailScreen(
                navController = navController,
                vetViewModel = vetViewModel,
                petListViewModel = petListViewModel,
                existingVetId = vetId
            )
        }
        composable(Destinations.ADD_VET) {
            VetDetailScreen(
                navController = navController,
                vetViewModel = vetViewModel,
                petListViewModel = petListViewModel,
                existingVetId = null
            )
        }
        composable(
            Destinations.ADD_VET_FOR_PET,
            arguments = listOf(navArgument("petId") { type = NavType.StringType })
        ) { backStackEntry ->
            val petId = backStackEntry.arguments?.getString("petId") ?: ""
            VetDetailScreen(
                navController = navController,
                vetViewModel = vetViewModel,
                petListViewModel = petListViewModel,
                existingVetId = null,
                linkToPetId = petId
            )
        }
        composable(Destinations.SETTINGS) {
            SettingsHubScreen(navController = navController, petListViewModel = petListViewModel, session = session)
        }
        composable(Destinations.HOUSEHOLD) {
            HouseholdScreen(navController = navController, householdViewModel = householdViewModel, currentUid = uid)
        }
    }
}
