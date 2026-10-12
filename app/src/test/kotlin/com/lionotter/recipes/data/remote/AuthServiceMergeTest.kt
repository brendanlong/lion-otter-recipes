package com.lionotter.recipes.data.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthServiceMergeTest {

    private lateinit var firestoreService: FirestoreService
    private lateinit var authService: AuthService
    private lateinit var guestUid: String

    @Before
    fun setup() {
        firestoreService = mockk(relaxed = true)
        coEvery { firestoreService.clearLocalData(any()) } returns true
        authService = AuthService(RuntimeEnvironment.getApplication(), firestoreService, mockk(), mockk())
        guestUid = (authService.authState.value as AuthState.Guest).uid
    }

    @Test
    fun `auth is Loading during migration and uid waiters block until it ends`() = runTest {
        val migrationStarted = CompletableDeferred<Unit>()
        val finishMigration = CompletableDeferred<Unit>()
        var stateDuringMigration: AuthState? = null

        val merge = async {
            authService.mergeGuestIntoGoogle { uid ->
                assertEquals(guestUid, uid)
                stateDuringMigration = authService.authState.value
                migrationStarted.complete(Unit)
                finishMigration.await()
                Result.success(Unit)
            }
        }
        migrationStarted.await()
        val awaitedUid = async { authService.awaitUid() }
        yield()
        assertFalse(awaitedUid.isCompleted)

        finishMigration.complete(Unit)
        assertTrue(merge.await().isSuccess)

        assertEquals(AuthState.Loading, stateDuringMigration)
        assertEquals(authService.authState.value.uid, awaitedUid.await())
        coVerify { firestoreService.clearLocalData(any()) }
    }

    @Test
    fun `failed migration restores the guest without clearing its data`() = runTest {
        val result = authService.mergeGuestIntoGoogle { Result.failure(IllegalStateException("offline")) }

        assertTrue(result.isFailure)
        assertEquals(AuthState.Guest(guestUid), authService.authState.value)
        coVerify(exactly = 0) { firestoreService.clearLocalData(any()) }
    }

    @Test
    fun `migration that throws restores the guest`() = runTest {
        val result = runCatching {
            authService.mergeGuestIntoGoogle { throw IllegalStateException("boom") }
        }

        assertTrue(result.isFailure)
        assertEquals(AuthState.Guest(guestUid), authService.authState.value)
    }
}
