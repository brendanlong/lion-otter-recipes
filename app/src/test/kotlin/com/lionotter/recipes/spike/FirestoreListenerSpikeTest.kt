package com.lionotter.recipes.spike

import android.util.Log
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.ImageSyncService
import com.lionotter.recipes.data.repository.RecipeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import com.lionotter.recipes.testutil.TestFirestoreService
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spike test: verify exactly which Firestore operations work under Robolectric.
 *
 * Results:
 * - Snapshot listeners (document + collection) fire after writes IF you pump the looper
 * - Task.await() never completes (Firestore's internal threading broken under Robolectric)
 * - Fire-and-forget writes (.set(), .update(), .delete() without await) work fine
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirestoreListenerSpikeTest {

    private val db by lazy { Firebase.firestore }

    @Before
    fun setup() {
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
    }

    @After
    fun teardown() {
        try {
            db.terminate()
        } catch (_: Exception) {}
    }

    private fun pumpLooper() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    // -----------------------------------------------------------------------
    // Core: do snapshot listeners fire?
    // -----------------------------------------------------------------------

    @Test
    fun `document snapshot listener fires after set`() {
        val docRef = db.collection("test").document("doc1")
        var receivedName: String? = null
        val latch = CountDownLatch(1)

        val reg = docRef.addSnapshotListener { snapshot, _ ->
            snapshot?.getString("name")?.let {
                receivedName = it
                latch.countDown()
            }
        }
        pumpLooper()

        docRef.set(mapOf("name" to "Recipe", "isFavorite" to false))
        pumpLooper()

        val ok = latch.await(2, TimeUnit.SECONDS)
        reg.remove()
        println("document set: fired=$ok, name=$receivedName")
        assert(ok) { "Document listener should fire after set()" }
    }

    @Test
    fun `document snapshot listener fires after update`() {
        val docRef = db.collection("test").document("doc2")
        val setLatch = CountDownLatch(1)
        val updateLatch = CountDownLatch(1)

        val reg = docRef.addSnapshotListener { snapshot, _ ->
            val fav = snapshot?.getBoolean("isFavorite")
            Log.d("SPIKE", "update test: fav=$fav exists=${snapshot?.exists()}")
            if (fav == false) setLatch.countDown()
            if (fav == true) updateLatch.countDown()
        }
        pumpLooper()

        // Initial write
        docRef.set(mapOf("name" to "Recipe", "isFavorite" to false))
        pumpLooper()
        val setOk = setLatch.await(2, TimeUnit.SECONDS)
        println("after set(): fired=$setOk")

        // .update() — this is what setFavorite uses
        docRef.update("isFavorite", true)
        pumpLooper()
        val updateOk = updateLatch.await(2, TimeUnit.SECONDS)
        println("after update(): fired=$updateOk")

        reg.remove()
        assert(setOk) { "Listener should fire after set()" }
        assert(updateOk) { "Listener should fire after update()" }
    }

    @Test
    fun `collection snapshot listener fires after document mutations`() {
        val collRef = db.collection("test-coll")
        val addLatch = CountDownLatch(1)
        var docCount = 0

        val reg = collRef.addSnapshotListener { snapshot, _ ->
            val count = snapshot?.documents?.size ?: 0
            Log.d("SPIKE", "collection: $count docs")
            if (count > 0) {
                docCount = count
                addLatch.countDown()
            }
        }
        pumpLooper()

        collRef.document("d1").set(mapOf("name" to "Recipe 1"))
        pumpLooper()

        val ok = addLatch.await(2, TimeUnit.SECONDS)
        reg.remove()
        println("collection set: fired=$ok, count=$docCount")
        assert(ok)
    }

    // -----------------------------------------------------------------------
    // The real scenario: set + update + two concurrent listeners
    // -----------------------------------------------------------------------

    @Test
    fun `set then update triggers both document and collection listeners`() {
        val collRef = db.collection("fav-test")
        val docRef = collRef.document("recipe-1")

        var docFavorite: Boolean? = null
        var collFavorite: Boolean? = null
        val docUpdateLatch = CountDownLatch(1)
        val collUpdateLatch = CountDownLatch(1)

        // Document listener
        val docReg = docRef.addSnapshotListener { snapshot, _ ->
            val fav = snapshot?.getBoolean("isFavorite")
            Log.d("SPIKE", "doc listener: fav=$fav")
            if (fav == true) {
                docFavorite = true
                docUpdateLatch.countDown()
            }
        }

        // Collection listener
        val collReg = collRef.addSnapshotListener { snapshot, _ ->
            val docs = snapshot?.documents ?: return@addSnapshotListener
            val fav = docs.firstOrNull()?.getBoolean("isFavorite")
            Log.d("SPIKE", "coll listener: fav=$fav, docs=${docs.size}")
            if (fav == true) {
                collFavorite = true
                collUpdateLatch.countDown()
            }
        }
        pumpLooper()

        // Initial set
        docRef.set(mapOf("name" to "Test Recipe", "isFavorite" to false))
        pumpLooper()

        // Partial update (like setFavorite)
        docRef.update("isFavorite", true)
        pumpLooper()

        val docOk = docUpdateLatch.await(2, TimeUnit.SECONDS)
        val collOk = collUpdateLatch.await(2, TimeUnit.SECONDS)

        docReg.remove()
        collReg.remove()

        println("After .update(isFavorite=true):")
        println("  doc listener fired:  $docOk (fav=$docFavorite)")
        println("  coll listener fired: $collOk (fav=$collFavorite)")

        assert(docOk) { "Document listener should fire after update()" }
        assert(collOk) { "Collection listener should fire after update()" }
    }

    // -----------------------------------------------------------------------
    // Task.await() — does NOT work without background pumping
    // -----------------------------------------------------------------------

    @Test
    fun `task addOnCompleteListener does not fire without background pumping`() {
        val docRef = db.collection("test").document("task-test")
        val latch = CountDownLatch(1)

        docRef.set(mapOf("name" to "Test"))
            .addOnCompleteListener { latch.countDown() }
        pumpLooper()

        val completed = latch.await(2, TimeUnit.SECONDS)
        println("Task addOnCompleteListener fired (no bg pump): $completed")
        // This is expected to NOT complete — documenting the limitation
    }

    // -----------------------------------------------------------------------
    // Main-thread looper pumping with coroutine on background dispatcher
    // -----------------------------------------------------------------------

    /**
     * Runs a suspend block on Dispatchers.Default while the main thread
     * continuously pumps the Robolectric looper. This allows Task.await()
     * to complete because the Task callbacks can fire on the pumped main looper
     * while the coroutine is suspended on a background thread.
     */
    private fun <T> runWithLooperPumping(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        var error: Throwable? = null
        val doneLatch = CountDownLatch(1)

        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                doneLatch.countDown()
            }
        }

        // Main thread: pump looper until the coroutine finishes
        val deadline = System.currentTimeMillis() + 10_000
        while (doneLatch.count > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("runWithLooperPumping timed out after 10s")
            }
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `set-then-get await - set task never completes`() {
        // .set().await() hangs because offline writes never "complete"
        val docRef = db.collection("test").document("set-await-test")

        var setCompleted = false
        try {
            runWithLooperPumping {
                docRef.set(mapOf("name" to "Test")).await()
                setCompleted = true
            }
        } catch (e: AssertionError) {
            // Expected: timed out
        }
        println("set().await() completed: $setCompleted")
        assert(!setCompleted) { "set().await() should NOT complete (offline, no server ack)" }
    }

    @Test
    fun `get await works after fire-and-forget write plus looper pump`() {
        // Write without await, pump looper, then .get().await() works
        val docRef = db.collection("test").document("get-await-test")
        docRef.set(mapOf("name" to "Fire and Forget"))
        pumpLooper()

        val result = runWithLooperPumping {
            docRef.get().await()
        }

        println("get().await() after fire-and-forget: ${result.getString("name")}")
        assert(result.getString("name") == "Fire and Forget")
    }

    @Test
    fun `real repository getRecipeByIdOnce works with looper pumping`() {
        // Full integration: fire-and-forget write → pumpLooper → suspend read via looper pumping
        val testFirestoreService = TestFirestoreService()
        val context = RuntimeEnvironment.getApplication()
        val imageSyncService = ImageSyncService(context)
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler { respond("", HttpStatusCode.NotFound) } }
        }
        val authService: AuthService = mockk()
        every { authService.authState } returns MutableStateFlow<AuthState>(AuthState.Guest(uid = "test-user"))
        every { authService.isGoogleUser() } returns false
        val imageDownloadService = ImageDownloadService(context, httpClient, imageSyncService, authService)
        val recipeRepo = RecipeRepository(testFirestoreService, imageDownloadService, authService)

        val recipe = com.lionotter.recipes.domain.model.Recipe(
            id = "looper-test",
            name = "Looper Recipe",
            instructionSections = emptyList(),
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(1700000000000),
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1700000000000)
        )

        recipeRepo.saveRecipe(recipe)
        pumpLooper()

        val result = runWithLooperPumping {
            recipeRepo.getRecipeByIdOnce("looper-test")
        }

        println("getRecipeByIdOnce: ${result?.name}")
        assert(result != null) { "Should find the recipe" }
        assert(result!!.name == "Looper Recipe") { "Name should match, got: ${result.name}" }
    }
}
