package com.lionotter.recipes.data.sync

import android.util.Log
import com.powersync.ExperimentalPowerSyncAPI
import com.powersync.PowerSyncDatabase
import com.powersync.sync.SyncOptions
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current sync status for the UI.
 */
enum class SyncStatus {
    /** Not signed in — sync not active */
    DISABLED,
    /** Signed in and actively syncing */
    SYNCING,
    /** Signed in and up to date */
    UP_TO_DATE,
    /** Signed in but currently offline */
    OFFLINE,
    /** Sync encountered an error */
    ERROR
}

/**
 * Manages the PowerSync connection lifecycle.
 *
 * - Connects PowerSync when user signs in
 * - Disconnects when user signs out
 * - Exposes sync status for the UI
 */
@Singleton
class SyncManager @Inject constructor(
    private val powerSyncDatabase: PowerSyncDatabase,
    private val supabaseClient: SupabaseClient,
    private val authRepository: AuthRepository,
    @com.lionotter.recipes.di.ApplicationScope private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SyncManager"
    }

    private val _syncStatus = MutableStateFlow(SyncStatus.DISABLED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var connector: RecipeSupabaseConnector? = null
    private var statusCollectionJob: Job? = null

    init {
        applicationScope.launch {
            authRepository.authState.collect { authState ->
                if (authState.isSignedIn) {
                    connectSync()
                } else {
                    disconnectSync()
                }
            }
        }
    }

    private suspend fun connectSync() {
        try {
            if (connector != null) {
                // Already connected
                return
            }
            Log.d(TAG, "Connecting PowerSync")
            _syncStatus.value = SyncStatus.SYNCING

            val newConnector = RecipeSupabaseConnector(supabaseClient)
            connector = newConnector
            @OptIn(ExperimentalPowerSyncAPI::class)
            powerSyncDatabase.connect(
                newConnector,
                options = SyncOptions(newClientImplementation = true),
            )

            // Observe sync status from PowerSync
            statusCollectionJob = applicationScope.launch {
                powerSyncDatabase.currentStatus.asFlow().collect { status ->
                    _syncStatus.value = when {
                        !authRepository.isSignedIn.value -> SyncStatus.DISABLED
                        status.connected && !status.downloading && !status.uploading -> SyncStatus.UP_TO_DATE
                        status.connected -> SyncStatus.SYNCING
                        else -> SyncStatus.OFFLINE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect PowerSync", e)
            _syncStatus.value = SyncStatus.ERROR
        }
    }

    private suspend fun disconnectSync() {
        if (connector == null) {
            // Not connected — nothing to disconnect
            _syncStatus.value = SyncStatus.DISABLED
            return
        }
        try {
            Log.d(TAG, "Disconnecting PowerSync")
            statusCollectionJob?.cancel()
            statusCollectionJob = null
            connector = null
            powerSyncDatabase.disconnect()
            _syncStatus.value = SyncStatus.DISABLED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect PowerSync", e)
        }
    }

    /**
     * Force a reconnection (e.g., user taps "Sync Now").
     */
    suspend fun forceSync() {
        if (!authRepository.isSignedIn.value) return
        try {
            disconnectSync()
            connectSync()
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed", e)
            _syncStatus.value = SyncStatus.ERROR
        }
    }
}
