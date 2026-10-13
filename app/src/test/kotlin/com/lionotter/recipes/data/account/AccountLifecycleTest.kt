package com.lionotter.recipes.data.account

import com.lionotter.recipes.data.local.LocalDataScope
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AccountTransition
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.data.remote.ReauthenticationRequiredException
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.testutil.AccountFakes
import com.lionotter.recipes.testutil.FakeLocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLifecycleTest {

    private val google = AuthState.Google(uid = "google-uid", email = "user@example.com")

    private fun TestScope.setUp(
        googleUser: AuthState.Google? = google,
        failingStore: Exception? = null
    ): Pair<AccountFakes, AccountLifecycle> {
        val fakes = AccountFakes(googleUser)
        val stores = setOf(
            FakeLocalStore("images", LocalDataScope.ACCOUNT, fakes.log),
            FakeLocalStore("imports", LocalDataScope.ACCOUNT, fakes.log, failingStore),
            FakeLocalStore("api key", LocalDataScope.DEVICE, fakes.log),
        )
        return fakes to fakes.lifecycle(stores, StandardTestDispatcher(testScheduler))
    }

    // Sign-out

    @Test
    fun `sign out clears the account's data, then signs out to a fresh guest`() = runTest {
        val (fakes, lifecycle) = setUp()

        assertEquals(SignOutResult.Success, lifecycle.signOut())

        assertEquals(
            listOf(
                "local: stop images",
                "local: stop imports",
                "firestore: clear (network off)",
                "local: clear images",
                "local: clear imports",
                "auth: sign out",
            ),
            fakes.log
        )
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
        assertTrue(fakes.firestore.failures.isEmpty())
    }

    @Test
    fun `sign out keeps device data such as the API key`() = runTest {
        val (fakes, lifecycle) = setUp()

        lifecycle.signOut()

        assertFalse(fakes.log.any { it.endsWith("api key") })
    }

    @Test
    fun `unsynced changes stop sign out without changing anything`() = runTest {
        val (fakes, lifecycle) = setUp()
        fakes.firestore.pendingWritesSynced = false

        assertEquals(SignOutResult.PendingWrites, lifecycle.signOut())

        assertTrue(fakes.log.isEmpty())
        assertEquals(google, fakes.auth.authState.value)
    }

    @Test
    fun `images not uploaded yet stop sign out unless forced`() = runTest {
        val (fakes, lifecycle) = setUp()
        fakes.pendingLocalImages = true

        assertEquals(SignOutResult.PendingWrites, lifecycle.signOut())
        assertEquals(google, fakes.auth.authState.value)

        assertEquals(SignOutResult.Success, lifecycle.signOut(force = true))
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `a store that fails to clear doesn't stop sign out or the other stores`() = runTest {
        val (fakes, lifecycle) = setUp(failingStore = IllegalStateException("disk full"))

        assertEquals(SignOutResult.Success, lifecycle.signOut())

        assertTrue("local: clear images" in fakes.log)
        assertEquals("auth: sign out", fakes.log.last())
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
        assertEquals(listOf(DataFailure(DataOp.CLEAR_PREVIOUS_ACCOUNT)), fakes.firestore.failures)
    }

    @Test
    fun `a Firestore cache that fails to clear is reported once`() = runTest {
        val (fakes, lifecycle) = setUp()
        fakes.firestore.clearSucceeds = false

        lifecycle.signOut()

        assertTrue(fakes.auth.authState.value is AuthState.Guest)
        assertEquals(listOf(DataFailure(DataOp.CLEAR_PREVIOUS_ACCOUNT)), fakes.firestore.failures)
    }

    @Test
    fun `a failed Firebase sign out leaves the user signed in and online, and fails`() = runTest {
        val (fakes, lifecycle) = setUp()
        val failure = IllegalStateException("boom")
        fakes.auth.signOutFailure = failure

        val thrown = runCatching { lifecycle.signOut() }.exceptionOrNull()

        // Coroutines' stack trace recovery may rethrow a copy caused by the original
        assertTrue(thrown === failure || thrown?.cause === failure)
        assertEquals(google, fakes.auth.authState.value)
        assertEquals("firestore: network on", fakes.log.last())
        assertTrue(fakes.firestore.failures.isEmpty())
    }

    @Test
    fun `background work stops before the transition and data is cleared during it`() = runTest {
        val fakes = AccountFakes(google)
        val states = mutableListOf<Pair<String, AuthState>>()
        val store = object : PerUserLocalData {
            override val scope = LocalDataScope.ACCOUNT
            override suspend fun stopBackgroundWork() {
                states += "stop" to fakes.auth.authState.value
            }

            override suspend fun clearUserData() {
                states += "clear" to fakes.auth.authState.value
            }
        }
        val lifecycle = fakes.lifecycle(setOf(store), StandardTestDispatcher(testScheduler))

        lifecycle.signOut()

        assertEquals(listOf("stop" to google, "clear" to AuthState.Loading), states)
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `a save finishing as sign out begins goes to the outgoing account, before anything is cleared`() = runTest {
        val (fakes, lifecycle) = setUp()
        fakes.work.startWorker(backgroundScope, finishDelayMs = 5_000) {
            fakes.log += "worker: save to ${fakes.auth.authState.value.uid}"
        }

        lifecycle.signOut()

        assertEquals("worker: save to ${google.uid}", fakes.log[2])
        assertEquals("firestore: clear (network off)", fakes.log[3])
        assertEquals(listOf(AccountTransition.LEAVE), fakes.auth.transitions)
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `background work that doesn't stop doesn't block sign out`() = runTest {
        val (fakes, lifecycle) = setUp()
        fakes.work.startWorker(backgroundScope, finishDelayMs = Long.MAX_VALUE)

        assertEquals(SignOutResult.Success, lifecycle.signOut())

        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `each sign out ends in a new guest`() = runTest {
        val (fakes, lifecycle) = setUp(googleUser = null)
        val firstGuest = fakes.auth.authState.value.uid

        lifecycle.signOut()

        assertNotEquals(firstGuest, fakes.auth.authState.value.uid)
    }

    // Account deletion

    @Test
    fun `deletion deletes the server data before clearing anything local`() = runTest {
        val (fakes, lifecycle) = setUp()

        lifecycle.deleteAccountAndData(null)

        assertEquals(
            listOf(
                "local: stop images",
                "local: stop imports",
                "local: stop api key",
                "remote: delete data",
                "remote: delete account",
                "firestore: clear (network off)",
                "local: clear images",
                "local: clear imports",
                "local: clear api key",
                "auth: sign out",
            ),
            fakes.log
        )
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `a recipe edit saving during deletion goes to the deleted account, not the next guest`() = runTest {
        val (fakes, lifecycle) = setUp()
        val savedTo = mutableListOf<String?>()
        fakes.work.startWorker(backgroundScope, finishDelayMs = 5_000) {
            savedTo += fakes.auth.authState.value.uid
        }

        lifecycle.deleteAccountAndData(null)

        assertEquals(listOf(google.uid), savedTo)
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    @Test
    fun `failed deletion keeps the user signed in with all local data`() = runTest {
        for (failure in listOf(AccountDeletionService.OfflineException(), ReauthenticationRequiredException())) {
            val (fakes, lifecycle) = setUp()
            fakes.remote.failure = failure

            val result = runCatching { lifecycle.deleteAccountAndData(null) }

            assertEquals(failure, result.exceptionOrNull())
            assertTrue(fakes.log.toString(), fakes.log.all { it.startsWith("local: stop") })
            assertEquals(google, fakes.auth.authState.value)
        }
    }

    @Test
    fun `if only the auth account survives deletion the user is still signed out and cleared`() = runTest {
        val (fakes, lifecycle) = setUp()
        val failure = AccountDeletionService.AuthAccountDeletionException(IllegalStateException("server"))
        fakes.remote.failure = failure

        val result = runCatching { lifecycle.deleteAccountAndData(null) }

        assertEquals(failure, result.exceptionOrNull())
        assertTrue("local: clear api key" in fakes.log)
        assertEquals("auth: sign out", fakes.log.last())
        assertTrue(fakes.auth.authState.value is AuthState.Guest)
    }

    // Guest sign-in

    @Test
    fun `auth is Loading during migration and uid waiters block until it ends`() = runTest {
        val (fakes, lifecycle) = setUp(googleUser = null)
        val guestUid = fakes.auth.authState.value.uid
        val migrationStarted = CompletableDeferred<Unit>()
        val finishMigration = CompletableDeferred<Unit>()
        var stateDuringMigration: AuthState? = null

        val merge = async {
            lifecycle.mergeGuestIntoGoogle { uid ->
                assertEquals(guestUid, uid)
                stateDuringMigration = fakes.auth.authState.value
                migrationStarted.complete(Unit)
                finishMigration.await()
                fakes.auth.googleUser = google
                Result.success(Unit)
            }
        }
        migrationStarted.await()
        val awaited = async { fakes.auth.authState.first { it !is AuthState.Loading } }
        yield()
        assertFalse(awaited.isCompleted)

        finishMigration.complete(Unit)
        assertTrue(merge.await().isSuccess)

        assertEquals(AuthState.Loading, stateDuringMigration)
        assertEquals(listOf(AccountTransition.MERGE), fakes.auth.transitions)
        assertEquals(google, awaited.await())
        assertEquals(google, fakes.auth.authState.value)
    }

    @Test
    fun `signing in clears only the guest's Firestore cache and goes online`() = runTest {
        val (fakes, lifecycle) = setUp(googleUser = null)

        lifecycle.mergeGuestIntoGoogle {
            fakes.auth.googleUser = google
            Result.success(Unit)
        }

        assertEquals(listOf("firestore: clear (network on)"), fakes.log)
    }

    @Test
    fun `failed migration restores the guest without clearing its data`() = runTest {
        val (fakes, lifecycle) = setUp(googleUser = null)
        val guest = fakes.auth.authState.value

        val result = lifecycle.mergeGuestIntoGoogle { Result.failure(IllegalStateException("offline")) }

        assertTrue(result.isFailure)
        assertEquals(guest, fakes.auth.authState.value)
        assertTrue(fakes.log.isEmpty())
    }

    @Test
    fun `migration that throws restores the guest`() = runTest {
        val (fakes, lifecycle) = setUp(googleUser = null)
        val guest = fakes.auth.authState.value

        val result = runCatching {
            lifecycle.mergeGuestIntoGoogle { throw IllegalStateException("boom") }
        }

        assertTrue(result.isFailure)
        assertEquals(guest, fakes.auth.authState.value)
    }
}
