package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.remote.AccountLeftException
import com.lionotter.recipes.data.remote.AccountTransition
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.testutil.FakeFirestoreCache
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Where fire-and-forget repository writes go when they are made during an account transition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteForCurrentUserTest {

    private val authService = AuthService(RuntimeEnvironment.getApplication(), webClientId = "client-id")
    private val firestore = FakeFirestoreCache(mutableListOf())
    private val failure = DataFailure(DataOp.SAVE_RECIPE, "recipe-1")
    private val writes = mutableListOf<String>()

    private val outgoing = AuthState.Google(uid = "google-uid", email = "user@example.com")
    private val nextGuest = AuthState.Guest(uid = "guest_next")

    @Test
    fun `a write made during sign-out is dropped, not written to the next guest`() = runTest {
        authService.endTransition(outgoing)
        authService.beginTransition(AccountTransition.LEAVE)

        authService.writeForCurrentUser(backgroundScope, firestore, failure) { uid -> writes += uid }
        // The transition may end before anything queued gets to run
        authService.endTransition(nextGuest)
        yield()

        assertTrue(writes.isEmpty())
        assertTrue(firestore.failures.isEmpty())
    }

    @Test
    fun `a write waiting on a sign-in is dropped if a sign-out follows before it runs`() = runTest {
        authService.beginTransition(AccountTransition.MERGE)
        authService.writeForCurrentUser(backgroundScope, firestore, failure) { uid -> writes += uid }
        yield()

        authService.endTransition(outgoing)
        authService.beginTransition(AccountTransition.LEAVE)
        authService.endTransition(nextGuest)
        yield()

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a write made during a guest's sign-in goes to the resulting account`() = runTest {
        authService.beginTransition(AccountTransition.MERGE)

        authService.writeForCurrentUser(backgroundScope, firestore, failure) { uid -> writes += uid }
        yield()
        assertTrue(writes.isEmpty())
        authService.endTransition(outgoing)
        yield()

        assertEquals(listOf(outgoing.uid), writes)
    }

    @Test
    fun `uid waiters fail when the account leaves instead of getting the next one`() = runTest {
        authService.endTransition(outgoing)
        authService.beginTransition(AccountTransition.LEAVE)

        val awaited = async { runCatching { authService.awaitUid() } }
        yield()

        assertTrue(awaited.isCompleted)
        assertTrue(awaited.await().exceptionOrNull() is AccountLeftException)

        authService.endTransition(nextGuest)
        assertEquals(nextGuest.uid, authService.awaitUid())
    }

    @Test
    fun `writes outside a transition go to the current account at once`() = runTest {
        authService.endTransition(outgoing)

        authService.writeForCurrentUser(backgroundScope, firestore, failure) { uid -> writes += uid }

        assertEquals(listOf(outgoing.uid), writes)
    }
}
