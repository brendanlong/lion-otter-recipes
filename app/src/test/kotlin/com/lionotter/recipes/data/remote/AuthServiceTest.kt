package com.lionotter.recipes.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthServiceTest {

    private val authService = AuthService(RuntimeEnvironment.getApplication(), webClientId = "client-id")

    @Test
    fun `starts as a guest with a stable UID`() {
        val state = authService.authState.value

        assertTrue(state is AuthState.Guest)
        assertEquals(state, authService.resolveState())
        assertEquals(state, AuthService(RuntimeEnvironment.getApplication(), "client-id").authState.value)
    }

    @Test
    fun `uid waiters block during a transition until it ends`() = runTest {
        val guest = authService.authState.value

        authService.beginTransition(AccountTransition.MERGE)
        val awaited = async { authService.awaitUid() }
        yield()
        assertFalse(awaited.isCompleted)
        assertFalse(authService.isGoogleUser())

        authService.endTransition(guest)

        assertEquals(guest.uid, awaited.await())
    }
}
