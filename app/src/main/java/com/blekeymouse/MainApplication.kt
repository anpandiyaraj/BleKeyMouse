package com.blekeymouse

import android.app.Application
import android.util.Log
import com.blekeymouse.di.AppModule

/**
 * Application class — initializes the application-scoped [BluetoothHidRepository]
 * singleton via [AppModule] at process start, before any Activity or Service runs.
 */
class MainApplication : Application() {

    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BleKeyMouse application starting")

        // Eagerly initialize the repository singleton so it's ready before
        // MainActivity requests ViewModel creation.
        AppModule.provideRepository(this)
    }
}
