package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.app
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.auth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles data migration when a guest user signs into a Google account.
 *
 * Strategy: create an ephemeral secondary [FirebaseApp] pointing at the
 * same project, sign the Google user in on that secondary instance, then
 * copy documents from the default (guest) Firestore cache to the secondary
 * (Google) Firestore. Once complete, sign the Google user in on the
 * default app and tear down the secondary app.
 *
 * This avoids the need for intermediate staging (Room) because the
 * guest user's data remains intact on the default app until the
 * migration is fully committed on the server via the secondary app.
 */
@Singleton
class AccountMigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "AccountMigrationService"
        /**
         * Each migration app gets a unique name to avoid a Firebase SDK bug
         * where [FirebaseApp.delete] does not cancel the internal heartbeat
         * DataStore's coroutine scope. Reusing the same name would trigger
         * "multiple DataStores active for the same file".
         */
        private val migrationCounter = AtomicInteger(0)
        private const val SERVER_TIMEOUT_MS = 30_000L

        internal const val MAX_DOCS_PER_BATCH = 100
        internal const val MAX_BATCH_BYTES = 4L * 1024 * 1024

        /**
         * Splits documents into batches of at most [MAX_DOCS_PER_BATCH] documents and
         * roughly [MAX_BATCH_BYTES] estimated bytes. A single oversized document gets
         * its own batch.
         */
        internal fun <T> chunkForBatches(documents: List<Pair<String, Map<String, T>>>): List<List<Pair<String, Map<String, T>>>> {
            val batches = mutableListOf<List<Pair<String, Map<String, T>>>>()
            var current = mutableListOf<Pair<String, Map<String, T>>>()
            var currentBytes = 0L
            for (doc in documents) {
                val size = estimateSize(doc.first) + estimateSize(doc.second)
                if (current.isNotEmpty() &&
                    (current.size >= MAX_DOCS_PER_BATCH || currentBytes + size > MAX_BATCH_BYTES)
                ) {
                    batches += current
                    current = mutableListOf()
                    currentBytes = 0L
                }
                current += doc
                currentBytes += size
            }
            if (current.isNotEmpty()) batches += current
            return batches
        }

        /** Approximates Firestore's storage size for a value (UTF-8 strings + fixed overheads). */
        internal fun estimateSize(value: Any?): Long = when (value) {
            null, is Boolean -> 1
            is String -> value.toByteArray(Charsets.UTF_8).size + 1L
            is Number -> 8
            is Map<*, *> -> value.entries.sumOf { estimateSize(it.key) + estimateSize(it.value) } + 32
            is Iterable<*> -> value.sumOf { estimateSize(it) } + 32
            is ByteArray -> value.size + 1L
            else -> 32
        }
    }

    /**
     * Migrates guest data to a Google account.
     *
     * 1. Creates a secondary FirebaseApp and signs in the Google user there
     * 2. Reads all recipes + meal plans from the default Firestore cache using [guestUid]
     * 3. Writes them to the secondary (Google) Firestore, skipping existing IDs
     * 4. Tears down the secondary app
     * 5. Signs in the Google user on the default app
     * 6. Returns success — the caller clears the Firestore cache and enables network
     *
     * If this method throws, the default app still has guest data intact — safe to retry.
     *
     * @param credential The Google [AuthCredential] to sign in with
     * @param guestUid The local guest UID whose data should be migrated
     */
    suspend fun migrateGuestData(credential: AuthCredential, guestUid: String): Result<Unit> {
        // 1. Create secondary app and sign in as Google user
        val migrationApp = createMigrationApp()
        try {
            val migrationAuth = com.google.firebase.auth.FirebaseAuth.getInstance(migrationApp)
            serverCall { migrationAuth.signInWithCredential(credential).await() }
            val googleUid = migrationAuth.currentUser?.uid
                ?: run {
                    cleanupMigrationApp(migrationApp)
                    return Result.failure(IllegalStateException("No user after sign-in on migration app"))
                }

            val migrationFirestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(migrationApp)

            // 2. Read guest data from default Firestore cache
            val defaultFirestore = Firebase.firestore

            val guestRecipes = readDocuments(
                defaultFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(guestUid).collection(FirestoreService.RECIPES_COLLECTION)
            )
            val guestMealPlans = readDocuments(
                defaultFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(guestUid).collection(FirestoreService.MEAL_PLANS_COLLECTION)
            )

            Log.d(TAG, "Read ${guestRecipes.size} recipes and ${guestMealPlans.size} meal plans from guest account")

            if (guestRecipes.isEmpty() && guestMealPlans.isEmpty()) {
                // Nothing to migrate — skip straight to sign-in
                cleanupMigrationApp(migrationApp)
                return completeSignIn(credential)
            }

            // 3. Read existing IDs in Google account to avoid overwriting
            val existingRecipeIds = readDocumentIds(
                migrationFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(googleUid).collection(FirestoreService.RECIPES_COLLECTION)
            )
            val existingMealPlanIds = readDocumentIds(
                migrationFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(googleUid).collection(FirestoreService.MEAL_PLANS_COLLECTION)
            )

            // 4. Write guest data to Google account, skipping existing IDs
            val recipesToMigrate = guestRecipes.filter { it.first !in existingRecipeIds }
            val mealPlansToMigrate = guestMealPlans.filter { it.first !in existingMealPlanIds }

            Log.d(TAG, "Migrating ${recipesToMigrate.size} recipes (${guestRecipes.size - recipesToMigrate.size} skipped)")
            Log.d(TAG, "Migrating ${mealPlansToMigrate.size} meal plans (${guestMealPlans.size - mealPlansToMigrate.size} skipped)")

            writeDocuments(
                migrationFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(googleUid).collection(FirestoreService.RECIPES_COLLECTION),
                recipesToMigrate
            )
            writeDocuments(
                migrationFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(googleUid).collection(FirestoreService.MEAL_PLANS_COLLECTION),
                mealPlansToMigrate
            )

            // 5. Tear down secondary app
            cleanupMigrationApp(migrationApp)

            // 6. Switch default app to Google user
            return completeSignIn(credential)
        } catch (e: CancellationException) {
            cleanupMigrationApp(migrationApp)
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Migration failed", e)
            cleanupMigrationApp(migrationApp)
            return Result.failure(e)
        }
    }

    /**
     * Completes the sign-in on the default app: signs in as Google.
     *
     * If sign-in fails, guest data is still intact on the default app. A sign-in that
     * times out is undone, in case it completes later.
     *
     * Firestore cache clearing and network enabling are NOT done here; the caller
     * ([AuthService.mergeGuestIntoGoogle]) does them while auth is still Loading.
     */
    private suspend fun completeSignIn(credential: AuthCredential): Result<Unit> {
        val signIn = Firebase.auth.signInWithCredential(credential)
        try {
            serverCall { signIn.await() }
        } catch (e: Exception) {
            Firebase.auth.signOut()
            // A timed-out sign-in can still complete later; undo it then too
            signIn.addOnSuccessListener { Firebase.auth.signOut() }
            throw e
        }
        return Result.success(Unit)
    }

    /**
     * Runs a server round trip with a timeout, so an offline or flaky connection fails
     * the migration (leaving guest data intact) instead of hanging sign-in forever.
     */
    private suspend fun <T> serverCall(block: suspend () -> T): T {
        return try {
            withTimeout(SERVER_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out waiting for the server", e)
        }
    }

    /**
     * Creates a secondary [FirebaseApp] for migration.
     *
     * Uses a unique name for each invocation to work around a Firebase SDK
     * bug: [FirebaseApp.delete] does not cancel the internal heartbeat
     * DataStore's coroutine scope, so reusing the same name would create a
     * second DataStore for the same file and crash with
     * "multiple DataStores active for the same file".
     */
    private fun createMigrationApp(): FirebaseApp {
        val appName = "migration-${migrationCounter.getAndIncrement()}"
        val defaultApp = Firebase.app
        return FirebaseApp.initializeApp(
            context,
            defaultApp.options,
            appName
        )
    }

    private suspend fun cleanupMigrationApp(app: FirebaseApp) {
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(app)
            firestore.terminate().await()
            firestore.clearPersistence().await()
        } catch (e: Exception) {
            Log.w(TAG, "Error during Firestore cleanup for migration app", e)
        } finally {
            try {
                app.delete()
                Log.d(TAG, "Migration app cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting migration app", e)
            }
        }
    }

    /**
     * Reads all documents from a collection's local cache,
     * returning pairs of (documentId, data).
     * Uses [Source.CACHE] since guest data is strictly offline.
     */
    private suspend fun readDocuments(
        collection: com.google.firebase.firestore.CollectionReference
    ): List<Pair<String, Map<String, Any?>>> {
        val snapshot = collection.get(Source.CACHE).await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data
            if (data != null) doc.id to data else null
        }
    }

    /**
     * Reads all document IDs from a collection on the server (the migration app's
     * cache is empty, so a cache fallback would wrongly report no existing documents).
     */
    private suspend fun readDocumentIds(
        collection: com.google.firebase.firestore.CollectionReference
    ): Set<String> {
        val snapshot = serverCall { collection.get(Source.SERVER).await() }
        return snapshot.documents.map { it.id }.toSet()
    }

    /**
     * Writes documents to a collection using batched writes, keeping each batch well
     * under Firestore's 10 MiB request limit (recipe documents can be large).
     */
    private suspend fun writeDocuments(
        collection: com.google.firebase.firestore.CollectionReference,
        documents: List<Pair<String, Map<String, Any?>>>
    ) {
        if (documents.isEmpty()) return

        val firestore = collection.firestore
        for (chunk in chunkForBatches(documents)) {
            val batch = firestore.batch()
            for ((id, data) in chunk) {
                batch.set(collection.document(id), data)
            }
            serverCall { batch.commit().await() }
        }
    }
}
