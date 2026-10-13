package com.lionotter.recipes.testutil

import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.data.account.AccountBoundWork
import com.lionotter.recipes.data.account.AccountLifecycle
import com.lionotter.recipes.data.local.LocalDataScope
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AccountTransition
import com.lionotter.recipes.data.remote.AuthSession
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.FirestoreCache
import com.lionotter.recipes.data.remote.RemoteAccountDeletion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory fakes of what [AccountLifecycle] composes. They append what happens to a shared
 * [log], so tests can check the order of a transition.
 */
class AccountFakes(googleUser: AuthState.Google? = null) {
    val log = mutableListOf<String>()
    val auth = FakeAuthSession(log, googleUser)
    val firestore = FakeFirestoreCache(log)
    val remote = FakeRemoteAccountDeletion(log, auth)
    val work = FakeAccountWork()
    var pendingLocalImages = false

    fun lifecycle(stores: Set<PerUserLocalData>, ioDispatcher: CoroutineDispatcher) =
        AccountLifecycle(auth, firestore, remote, { pendingLocalImages }, work, stores, ioDispatcher)
}

/** Firebase Auth: a Google user or a guest UID, numbered so each fresh guest is distinct. */
class FakeAuthSession(private val log: MutableList<String>, var googleUser: AuthState.Google?) : AuthSession {
    private var guestCount = 0
    private var guestUid: String? = null

    /** Thrown by [signOutAndForgetGuest] instead of signing out. */
    var signOutFailure: Exception? = null

    private val _authState = MutableStateFlow(resolveState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** The kind of each transition begun, in order. */
    val transitions = mutableListOf<AccountTransition>()

    override fun beginTransition(kind: AccountTransition) {
        transitions += kind
        _authState.value = AuthState.Loading
    }

    override fun resolveState(): AuthState =
        googleUser ?: AuthState.Guest(guestUid ?: "guest_${++guestCount}".also { guestUid = it })

    override fun endTransition(state: AuthState) {
        _authState.value = state
    }

    override suspend fun signOutAndForgetGuest() {
        signOutFailure?.let { throw it }
        log += "auth: sign out"
        googleUser = null
        guestUid = null
    }
}

/**
 * Background workers: each started one runs until [cancelAll], then takes its own time to
 * finish (like a worker saving under `NonCancellable`).
 */
class FakeAccountWork : AccountBoundWork {
    private val running = MutableStateFlow(0)
    private val cancelled = CompletableDeferred<Unit>()

    /** Starts a worker in [scope] that, once cancelled, waits [finishDelayMs] and then runs [onFinish]. */
    fun startWorker(scope: CoroutineScope, finishDelayMs: Long, onFinish: () -> Unit = {}) {
        running.update { it + 1 }
        scope.launch {
            try {
                cancelled.await()
                delay(finishDelayMs)
                onFinish()
            } finally {
                running.update { it - 1 }
            }
        }
    }

    override fun cancelAll() {
        cancelled.complete(Unit)
    }

    override suspend fun awaitStopped() {
        running.first { it == 0 }
    }
}

class FakeFirestoreCache(private val log: MutableList<String>) : FirestoreCache {
    var pendingWritesSynced = true

    /** If set, [awaitPendingWrites] waits for it instead of using [pendingWritesSynced]. */
    var pendingWritesResult: CompletableDeferred<Boolean>? = null
    var clearSucceeds = true
    val failures = mutableListOf<DataFailure>()

    override suspend fun awaitPendingWrites(timeoutMs: Long): Boolean =
        pendingWritesResult?.await() ?: pendingWritesSynced

    override suspend fun clearLocalData(enableNetwork: Boolean): Boolean {
        log += "firestore: clear (network ${if (enableNetwork) "on" else "off"})"
        return clearSucceeds
    }

    override suspend fun enableNetwork() {
        log += "firestore: network on"
    }

    override fun reportFailure(failure: DataFailure, cause: Throwable?) {
        failures += failure
    }
}

/** Deletes the server data; on success the Firebase user is gone too. */
class FakeRemoteAccountDeletion(
    private val log: MutableList<String>,
    private val auth: FakeAuthSession
) : RemoteAccountDeletion {
    var failure: Exception? = null
    val credentials = mutableListOf<AuthCredential?>()

    override suspend fun deleteAllUserData(reauthCredential: AuthCredential?) {
        credentials += reauthCredential
        val failure = failure
        if (failure is AccountDeletionService.AuthAccountDeletionException) {
            log += "remote: delete data"
            throw failure
        }
        if (failure != null) throw failure
        log += "remote: delete data"
        log += "remote: delete account"
        auth.googleUser = null
    }
}

class FakeLocalStore(
    private val name: String,
    override val scope: LocalDataScope,
    private val log: MutableList<String>,
    private val failure: Exception? = null
) : PerUserLocalData {
    override suspend fun stopBackgroundWork() {
        log += "local: stop $name"
    }

    override suspend fun clearUserData() {
        log += "local: clear $name"
        failure?.let { throw it }
    }
}
