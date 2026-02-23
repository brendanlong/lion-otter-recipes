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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles data migration when a guest (anonymous) user signs into an
 * existing Google account (the "NeedsMerge" flow).
 *
 * Strategy: create an ephemeral secondary [FirebaseApp] pointing at the
 * same project, sign the Google user in on that secondary instance, then
 * copy documents from the default (anonymous) Firestore to the secondary
 * (Google) Firestore. Once complete, sign the Google user in on the
 * default app and tear down the secondary app.
 *
 * This avoids the need for intermediate staging (Room) because the
 * anonymous user's data remains intact on the default app until the
 * migration is fully committed on the server via the secondary app.
 */
@Singleton
class AccountMigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "AccountMigrationService"
        private const val MIGRATION_APP_NAME = "migration"
    }

    /**
     * Migrates guest data to an existing Google account.
     *
     * 1. Creates a secondary FirebaseApp and signs in the Google user there
     * 2. Reads all recipes + meal plans from the default (anonymous) Firestore cache
     * 3. Writes them to the secondary (Google) Firestore, skipping existing IDs
     * 4. Tears down the secondary app
     * 5. Signs in the Google user on the default app
     * 6. Clears the anonymous Firestore cache and enables network
     *
     * If this method throws, the default app is still signed in as anonymous
     * with all guest data intact — safe to retry.
     */
    suspend fun migrateGuestData(credential: AuthCredential): Result<Unit> {
        // 1. Create secondary app and sign in as Google user
        val migrationApp = createMigrationApp()
        try {
            val migrationAuth = com.google.firebase.auth.FirebaseAuth.getInstance(migrationApp)
            migrationAuth.signInWithCredential(credential).await()
            val googleUid = migrationAuth.currentUser?.uid
                ?: run {
                    cleanupMigrationApp(migrationApp)
                    return Result.failure(IllegalStateException("No user after sign-in on migration app"))
                }

            val migrationFirestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(migrationApp)

            // 2. Read guest data from default (anonymous) Firestore cache
            val anonymousUid = Firebase.auth.currentUser?.uid
                ?: run {
                    cleanupMigrationApp(migrationApp)
                    return Result.failure(IllegalStateException("No anonymous user"))
                }

            val defaultFirestore = Firebase.firestore

            val guestRecipes = readDocuments(
                defaultFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(anonymousUid).collection(FirestoreService.RECIPES_COLLECTION)
            )
            val guestMealPlans = readDocuments(
                defaultFirestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(anonymousUid).collection(FirestoreService.MEAL_PLANS_COLLECTION)
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
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            cleanupMigrationApp(migrationApp)
            return Result.failure(e)
        }
    }

    /**
     * Completes the sign-in on the default app: signs in as Google,
     * clears the anonymous Firestore cache, and enables network.
     *
     * Sign-in happens first so that if it fails, the anonymous user's
     * local cache is still intact and nothing has been lost.
     */
    private suspend fun completeSignIn(credential: AuthCredential): Result<Unit> {
        // Sign in as Google first — if this fails, anonymous data is still intact
        Firebase.auth.signInWithCredential(credential).await()
        // Now clear the anonymous Firestore cache to prevent orphaned documents
        // from syncing when network is re-enabled. The auth state listener is
        // suppressed (Loading), so the UID change won't trigger repository
        // re-subscriptions yet.
        firestoreService.clearLocalData()
        firestoreService.enableNetwork()
        return Result.success(Unit)
    }

    private fun createMigrationApp(): FirebaseApp {
        // Clean up any leftover migration app from a previous crash
        try {
            FirebaseApp.getInstance(MIGRATION_APP_NAME).delete()
        } catch (_: IllegalStateException) {
            // No existing migration app — expected
        }

        val defaultApp = Firebase.app
        return FirebaseApp.initializeApp(
            context,
            defaultApp.options,
            MIGRATION_APP_NAME
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
     * Reads all document IDs from a collection.
     */
    private suspend fun readDocumentIds(
        collection: com.google.firebase.firestore.CollectionReference
    ): Set<String> {
        val snapshot = collection.get().await()
        return snapshot.documents.map { it.id }.toSet()
    }

    /**
     * Writes documents to a collection using batched writes.
     * Firestore batches support up to 500 operations.
     */
    private suspend fun writeDocuments(
        collection: com.google.firebase.firestore.CollectionReference,
        documents: List<Pair<String, Map<String, Any?>>>
    ) {
        if (documents.isEmpty()) return

        val firestore = collection.firestore
        for (chunk in documents.chunked(500)) {
            val batch = firestore.batch()
            for ((id, data) in chunk) {
                batch.set(collection.document(id), data)
            }
            batch.commit().await()
        }
    }
}
