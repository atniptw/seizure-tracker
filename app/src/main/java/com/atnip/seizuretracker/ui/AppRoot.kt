package com.atnip.seizuretracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atnip.seizuretracker.ui.dashboard.DashboardScreen
import com.atnip.seizuretracker.ui.dogprofile.DogProfileScreen
import com.atnip.seizuretracker.ui.export.ExportScreen
import com.atnip.seizuretracker.ui.household.HouseholdViewModel
import com.atnip.seizuretracker.ui.navigation.Destinations
import com.atnip.seizuretracker.ui.seizure.AddEditSeizureScreen
import com.atnip.seizuretracker.ui.seizure.SeizureDetailScreen
import com.atnip.seizuretracker.ui.seizure.SeizureHistoryScreen
import com.atnip.seizuretracker.ui.seizure.SeizureListViewModel
import com.atnip.seizuretracker.ui.session.SessionState
import com.atnip.seizuretracker.ui.session.SessionViewModel
import com.atnip.seizuretracker.ui.welcome.WelcomeScreen

@Composable
fun AppRoot(session: SessionViewModel) {
    val sessionState by session.state.collectAsState()

    when (val s = sessionState) {
        is SessionState.Loading -> LoadingScreen()
        is SessionState.NeedsSetup -> WelcomeScreen(session)
        is SessionState.Ready -> {
            val householdViewModel: HouseholdViewModel = viewModel(
                key = s.householdId,
                factory = HouseholdViewModel.factory(s.householdId)
            )
            val seizureListViewModel: SeizureListViewModel = viewModel(
                key = s.householdId,
                factory = SeizureListViewModel.factory(s.householdId)
            )
            MainNavHost(
                householdId = s.householdId,
                displayName = s.displayName,
                uid = s.uid,
                householdViewModel = householdViewModel,
                seizureListViewModel = seizureListViewModel
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
    householdId: String,
    displayName: String,
    uid: String,
    householdViewModel: HouseholdViewModel,
    seizureListViewModel: SeizureListViewModel
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Destinations.DASHBOARD) {
        composable(Destinations.DASHBOARD) {
            DashboardScreen(
                navController = navController,
                householdViewModel = householdViewModel,
                seizureListViewModel = seizureListViewModel
            )
        }
        composable(Destinations.HISTORY) {
            SeizureHistoryScreen(navController = navController, seizureListViewModel = seizureListViewModel)
        }
        composable(Destinations.ADD_SEIZURE) {
            AddEditSeizureScreen(
                navController = navController,
                seizureListViewModel = seizureListViewModel,
                displayName = displayName,
                uid = uid,
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
        composable(Destinations.DOG_PROFILE) {
            DogProfileScreen(navController = navController, householdViewModel = householdViewModel)
        }
        composable(Destinations.EXPORT) {
            ExportScreen(
                navController = navController,
                householdViewModel = householdViewModel,
                seizureListViewModel = seizureListViewModel
            )
        }
    }
}
