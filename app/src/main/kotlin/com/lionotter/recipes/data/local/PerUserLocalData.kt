package com.lionotter.recipes.data.local

/**
 * Data kept on the device that belongs to whoever uses the app, so it must not outlive their
 * account here. Every store of such data implements this and is bound into the
 * `Set<PerUserLocalData>` in `LocalUserDataModule`;
 * [com.lionotter.recipes.data.account.AccountLifecycle] clears the set on sign-out and account
 * deletion (see DESIGN.md "Authentication and Account Lifecycle"). `LocalUserDataRegistryTest`
 * fails when a new store isn't registered.
 */
interface PerUserLocalData {
    /** When the data is cleared. */
    val scope: LocalDataScope

    /**
     * Called before the transition starts, while the outgoing account is still signed in: stop
     * background work that could still save into it, so a save already under way goes to the
     * outgoing account (and is cleared with it) rather than the next one. Must not wait long.
     */
    suspend fun stopBackgroundWork() {}

    /**
     * Deletes all of the data. Throws if some of it could not be deleted (the transition still
     * completes, and the user is told the previous account's data may remain).
     */
    suspend fun clearUserData()
}

enum class LocalDataScope {
    /**
     * Belongs to the signed-in account: cleared whenever it leaves the device (sign-out and
     * account deletion). Kept when a guest signs in, since the guest's data moves to the account.
     */
    ACCOUNT,

    /**
     * Belongs to the device rather than an account, but is the user's (or contains their
     * content): kept across sign-out and only cleared when the user deletes their account.
     */
    DEVICE,
}
