package com.atnip.seizuretracker

import android.app.Application
import com.google.firebase.FirebaseApp

class SeizureTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
