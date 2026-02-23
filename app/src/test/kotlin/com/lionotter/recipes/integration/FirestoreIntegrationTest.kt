package com.lionotter.recipes.integration

import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.ImageSyncService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import com.lionotter.recipes.testutil.TestFirestoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch

/**
 * Base class for integration tests that use REAL Firestore SDK + real repositories.
 *
 * Firestore runs in offline mode with an in-memory cache, so tests don't need
 * network access. Snapshot listeners fire after writes when the looper is pumped.
 *
 * Two helpers are provided for dealing with Robolectric's paused main looper:
 *
 * - [pumpLooper]: call after fire-and-forget writes (.set(), .update(), .delete())
 *   to flush data into the local cache and trigger snapshot listeners.
 *
 * - [runSuspending]: runs a suspend block on [Dispatchers.Default] while the main
 *   thread continuously pumps the looper. This allows Firestore's `.get().await()`
 *   (which reads from the local cache) to complete. Use for one-shot suspend reads
 *   like `getRecipeByIdOnce()`, etc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class FirestoreIntegrationTest {

    private val db by lazy { Firebase.firestore }
    protected lateinit var firestoreService: TestFirestoreService
    protected lateinit var recipeRepository: RecipeRepository
    protected lateinit var mealPlanRepository: MealPlanRepository

    @Before
    fun baseSetup() {
        val context = RuntimeEnvironment.getApplication()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("test-project")
                    .setApplicationId("1:123:android:abc")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }

        val settings = firestoreSettings {
            setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
        }
        db.firestoreSettings = settings
        db.disableNetwork()

        firestoreService = TestFirestoreService()
        val imageSyncService = ImageSyncService(context)
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler { respond("", HttpStatusCode.NotFound) } }
        }
        val imageDownloadService = ImageDownloadService(context, httpClient, imageSyncService)
        val authService: AuthService = mockk()
        every { authService.currentUserId } returns MutableStateFlow("test-user")
        recipeRepository = RecipeRepository(firestoreService, imageDownloadService, authService)
        mealPlanRepository = MealPlanRepository(firestoreService)
    }

    @After
    fun baseTeardown() {
        try {
            db.terminate()
        } catch (_: Exception) {}
    }

    /**
     * Pump the Robolectric main looper so Firestore snapshot listeners fire.
     * Call after every write operation (.set(), .update(), .delete()).
     */
    protected fun pumpLooper() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    /**
     * Run a suspend function while continuously pumping the main looper.
     *
     * The suspend block runs on [Dispatchers.Default] so it doesn't block the
     * main thread. Meanwhile, the main thread pumps [ShadowLooper.idleMainLooper]
     * in a loop, allowing Firestore Task callbacks (from `.get().await()`) to fire.
     *
     * Note: write Tasks (`.set().await()`, `.update().await()`) will never complete
     * because offline Firestore never receives a server acknowledgement. Only use
     * this for **read** operations that resolve from the local cache.
     */
    protected fun <T> runSuspending(
        timeoutMs: Long = 10_000,
        block: suspend CoroutineScope.() -> T
    ): T {
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        var error: Throwable? = null
        val doneLatch = CountDownLatch(1)

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                doneLatch.countDown()
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (doneLatch.count > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("runSuspending timed out after ${timeoutMs}ms")
            }
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
