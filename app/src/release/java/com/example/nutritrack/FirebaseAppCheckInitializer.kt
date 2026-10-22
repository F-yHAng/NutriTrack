package com.example.nutritrack

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize

internal object FirebaseAppCheckInitializer {
    fun initialize(application: Application): Boolean {
        return try {
            Firebase.initialize(context = application) ?: return false
            Firebase.appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}
