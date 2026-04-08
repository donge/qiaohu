package com.example.parlor

import android.app.Application
import android.util.Log

/**
 * Application subclass.
 * Currently only needed as the android:name entry point for potential future
 * dependency injection / global initialisation.
 */
class ParlourApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("ParlourApp", "Application created")
    }
}
