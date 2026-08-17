package com.atnip.seizuretracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atnip.seizuretracker.ui.AppRoot
import com.atnip.seizuretracker.ui.accessibility.AccessibilityViewModel
import com.atnip.seizuretracker.ui.session.SessionViewModel
import com.atnip.seizuretracker.ui.theme.SeizureTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val accessibilityViewModel: AccessibilityViewModel = viewModel(
                factory = AccessibilityViewModel.factory(applicationContext)
            )
            val a11y by accessibilityViewModel.settings.collectAsState()

            SeizureTrackerTheme(a11y = a11y) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val sessionViewModel: SessionViewModel = viewModel(
                        factory = SessionViewModel.factory(applicationContext)
                    )
                    AppRoot(session = sessionViewModel, accessibilityViewModel = accessibilityViewModel)
                }
            }
        }
    }
}
