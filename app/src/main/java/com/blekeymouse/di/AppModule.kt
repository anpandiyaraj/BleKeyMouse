package com.blekeymouse.di

import android.content.Context
import com.blekeymouse.data.BluetoothHidRepository

/**
 * Manual dependency injection module — no Hilt/Dagger for simplicity.
 *
 * [AppModule] provides a single application-scoped [BluetoothHidRepository] instance.
 * It is initialized in [com.blekeymouse.MainApplication] and accessed statically.
 *
 * This keeps the singleton lifecycle tied to the Application (not an Activity),
 * ensuring the repository survives configuration changes and background operation.
 */
object AppModule {

    @Volatile
    private var repository: BluetoothHidRepository? = null

    /**
     * Returns the application-scoped [BluetoothHidRepository] singleton.
     * Thread-safe double-checked locking initialization.
     */
    fun provideRepository(context: Context): BluetoothHidRepository {
        return repository ?: synchronized(this) {
            repository ?: BluetoothHidRepository(context.applicationContext).also {
                repository = it
            }
        }
    }
}
