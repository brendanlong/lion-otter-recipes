package com.lionotter.recipes.data.account

/**
 * Background work for the signed-in account (imports, edits, regeneration, export), which
 * [AccountLifecycle] stops before the account leaves the device. Implemented by
 * `RunningAccountWork` (worker).
 */
interface AccountBoundWork {
    /** Cancels all such work, queued or running. */
    fun cancelAll()

    /**
     * Suspends until no such work is running in this process. Cancelled work can still be
     * finishing a save (under `NonCancellable`), which then goes to the account it started in.
     */
    suspend fun awaitStopped()
}
