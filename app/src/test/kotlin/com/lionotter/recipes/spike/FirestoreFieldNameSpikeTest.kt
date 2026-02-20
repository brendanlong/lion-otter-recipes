package com.lionotter.recipes.spike

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.PropertyName
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spike: what field name does Firestore actually use for a Kotlin
 * `val isFavorite: Boolean` when serialized via toObject/set?
 *
 * JavaBean convention strips the "is" prefix from boolean getters,
 * so `isFavorite()` → property "favorite".
 * But .update("isFavorite", true) writes to literal field "isFavorite".
 * If these differ, .update() creates a NEW field instead of updating
 * the existing one, and snapshot listeners would see the wrong value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirestoreFieldNameSpikeTest {

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
        try { db.terminate() } catch (_: Exception) {}
    }

    private fun pumpLooper() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    // Simple DTO matching RecipeDto's OLD pattern (buggy)
    data class TestDto(
        val name: String = "",
        val isFavorite: Boolean = false
    )

    // Fixed DTO with @PropertyName to force consistent field naming
    data class FixedTestDto(
        val name: String = "",
        @get:PropertyName("isFavorite") @set:PropertyName("isFavorite")
        var isFavorite: Boolean = false
    )

    @Test
    fun `check what field name Firestore uses for isFavorite`() {
        val docRef = db.collection("test").document("field-check")
        val latch = CountDownLatch(1)
        var rawFields: Map<String, Any>? = null

        // Write via .set() with a DTO object
        val dto = TestDto(name = "Test", isFavorite = false)
        docRef.set(dto)
        pumpLooper()

        // Read back the raw document data
        val reg = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                rawFields = snapshot.data
                latch.countDown()
            }
        }
        pumpLooper()

        latch.await(2, TimeUnit.SECONDS)
        reg.remove()

        println("=== Raw Firestore fields after .set(TestDto) ===")
        rawFields?.forEach { (key, value) ->
            println("  '$key' = $value (${value::class.simpleName})")
        }

        // Check which field name was used
        val hasFavorite = rawFields?.containsKey("favorite") ?: false
        val hasIsFavorite = rawFields?.containsKey("isFavorite") ?: false
        println("Has 'favorite': $hasFavorite")
        println("Has 'isFavorite': $hasIsFavorite")
    }

    @Test
    fun `update isFavorite then check both field names`() {
        val docRef = db.collection("test").document("update-check")

        // Initial set with DTO
        docRef.set(TestDto(name = "Test", isFavorite = false))
        pumpLooper()

        // Update using the literal string "isFavorite" (like RecipeRepository does)
        docRef.update("isFavorite", true)
        pumpLooper()

        val latch = CountDownLatch(1)
        var rawFields: Map<String, Any>? = null

        val reg = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                rawFields = snapshot.data
                latch.countDown()
            }
        }
        pumpLooper()

        latch.await(2, TimeUnit.SECONDS)
        reg.remove()

        println("=== Raw fields after .set(dto) then .update('isFavorite', true) ===")
        rawFields?.forEach { (key, value) ->
            println("  '$key' = $value (${value::class.simpleName})")
        }

        // Now deserialize back to DTO and check
        val latch2 = CountDownLatch(1)
        var deserialized: TestDto? = null

        val reg2 = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                deserialized = snapshot.toObject(TestDto::class.java)
                latch2.countDown()
            }
        }
        pumpLooper()

        latch2.await(2, TimeUnit.SECONDS)
        reg2.remove()

        println("=== Deserialized TestDto ===")
        println("  name: ${deserialized?.name}")
        println("  isFavorite: ${deserialized?.isFavorite}")
        println()

        if (rawFields?.containsKey("favorite") == true && rawFields?.containsKey("isFavorite") == true) {
            println("BUG CONFIRMED: .set() stored 'favorite', .update() created separate 'isFavorite'")
            println("The DTO reads 'favorite' (=${rawFields!!["favorite"]}), ignoring 'isFavorite' (=${rawFields!!["isFavorite"]})")
        }
    }

    @Test
    fun `PropertyName annotation fixes the field name mismatch`() {
        val docRef = db.collection("test").document("fixed-check")

        // Write with the fixed DTO
        docRef.set(FixedTestDto(name = "Test", isFavorite = false))
        pumpLooper()

        // Check what field name was stored
        val latch1 = CountDownLatch(1)
        var rawFields: Map<String, Any>? = null
        val reg1 = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                rawFields = snapshot.data
                latch1.countDown()
            }
        }
        pumpLooper()
        latch1.await(2, TimeUnit.SECONDS)
        reg1.remove()

        println("=== Raw fields after .set(FixedTestDto) ===")
        rawFields?.forEach { (key, value) ->
            println("  '$key' = $value (${value::class.simpleName})")
        }

        // With @PropertyName, .set() should now store "isFavorite" (not "favorite")
        assert(rawFields?.containsKey("isFavorite") == true) {
            "FixedTestDto should store field as 'isFavorite', got keys: ${rawFields?.keys}"
        }
        assert(rawFields?.containsKey("favorite") != true) {
            "FixedTestDto should NOT store field as 'favorite'"
        }

        // Now update and verify it modifies the same field
        docRef.update("isFavorite", true)
        pumpLooper()

        val latch2 = CountDownLatch(1)
        var rawFields2: Map<String, Any>? = null
        val reg2 = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                rawFields2 = snapshot.data
                latch2.countDown()
            }
        }
        pumpLooper()
        latch2.await(2, TimeUnit.SECONDS)
        reg2.remove()

        println("=== Raw fields after .update('isFavorite', true) ===")
        rawFields2?.forEach { (key, value) ->
            println("  '$key' = $value (${value::class.simpleName})")
        }

        // Should have only ONE isFavorite field, and it should be true
        assert(rawFields2?.containsKey("isFavorite") == true)
        assert(rawFields2?.get("isFavorite") == true) {
            "isFavorite should be true after update, got ${rawFields2?.get("isFavorite")}"
        }
        assert(rawFields2?.containsKey("favorite") != true) {
            "Should NOT have a separate 'favorite' field"
        }

        // Deserialize and verify
        val latch3 = CountDownLatch(1)
        var deserialized: FixedTestDto? = null
        val reg3 = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                deserialized = snapshot.toObject(FixedTestDto::class.java)
                latch3.countDown()
            }
        }
        pumpLooper()
        latch3.await(2, TimeUnit.SECONDS)
        reg3.remove()

        println("=== Deserialized FixedTestDto ===")
        println("  name: ${deserialized?.name}")
        println("  isFavorite: ${deserialized?.isFavorite}")

        assert(deserialized?.isFavorite == true) {
            "Deserialized isFavorite should be true after update, got ${deserialized?.isFavorite}"
        }
        println("FIX CONFIRMED: @PropertyName makes .set() and .update() use the same field name")
    }
}
