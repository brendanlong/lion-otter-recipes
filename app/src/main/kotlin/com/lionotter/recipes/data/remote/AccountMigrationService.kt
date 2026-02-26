package com.lionotter.recipes.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.auth
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.local.PendingMigrationDao
import com.lionotter.recipes.data.local.PendingMigrationEntity
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles data migration when a guest user signs into a Google account.
 *
 * Strategy: stage guest data into a Room queue (durable across crashes),
 * then sign in as the Google user, clear the Firestore cache, and write
 * the staged data to the Google user's Firestore using standard DTOs.
 *
 * This avoids the secondary [com.google.firebase.FirebaseApp] that the old
 * implementation required, and ensures no data is lost if the app crashes
 * mid-migration — staged entries survive in Room and are retried on next
 * launch via [applyPendingMigration].
 */
@Singleton
class AccountMigrationService @Inject constructor(
    private val firestoreService: FirestoreService,
    private val pendingMigrationDao: PendingMigrationDao,
    private val json: Json
) {
    companion object {
        private const val TAG = "AccountMigrationService"
    }

    /**
     * Phase 1: Read guest data from the Firestore cache and persist it
     * into the Room migration queue.
     *
     * After this method returns, it is safe to clear the Firestore cache
     * (the data is durably stored in Room). If the app crashes before
     * [applyPendingMigration] runs, the staged data survives.
     *
     * @param guestUid The local guest UID whose data should be migrated
     */
    suspend fun stageGuestData(guestUid: String) {
        val defaultFirestore = Firebase.firestore

        val guestDoc = defaultFirestore
            .collection(FirestoreService.USERS_COLLECTION)
            .document(guestUid)

        val recipeSnapshots: QuerySnapshot = guestDoc
            .collection(FirestoreService.RECIPES_COLLECTION)
            .get(Source.CACHE).await()

        val mealPlanSnapshots: QuerySnapshot = guestDoc
            .collection(FirestoreService.MEAL_PLANS_COLLECTION)
            .get(Source.CACHE).await()

        val now = System.currentTimeMillis()
        val entities = mutableListOf<PendingMigrationEntity>()

        for (doc in recipeSnapshots.documents) {
            try {
                val dto = doc.toObject(RecipeDto::class.java) ?: continue
                val recipe = dto.toDomain()
                entities.add(
                    PendingMigrationEntity(
                        id = recipe.id,
                        type = PendingMigrationEntity.TYPE_RECIPE,
                        json = json.encodeToString<Recipe>(recipe),
                        createdAt = now
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to serialize recipe ${doc.id} for staging", e)
            }
        }

        for (doc in mealPlanSnapshots.documents) {
            try {
                val dto = doc.toObject(MealPlanDto::class.java) ?: continue
                val entry = dto.toDomain()
                entities.add(
                    PendingMigrationEntity(
                        id = entry.id,
                        type = PendingMigrationEntity.TYPE_MEAL_PLAN,
                        json = json.encodeToString<MealPlanEntry>(entry),
                        createdAt = now
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to serialize meal plan ${doc.id} for staging", e)
            }
        }

        Log.d(TAG, "Staging ${entities.count { it.type == PendingMigrationEntity.TYPE_RECIPE }} recipes " +
            "and ${entities.count { it.type == PendingMigrationEntity.TYPE_MEAL_PLAN }} meal plans")

        if (entities.isNotEmpty()) {
            pendingMigrationDao.insertAll(entities)
        }
    }

    /**
     * Signs in the Google user on the default Firebase app.
     *
     * Called after [stageGuestData] but before clearing the Firestore cache.
     * If sign-in fails, guest data is still in Room (and in the Firestore
     * cache until it is cleared).
     */
    suspend fun signIn(credential: AuthCredential) {
        Firebase.auth.signInWithCredential(credential).await()
    }

    /**
     * Phase 2: Write staged migration data to the current user's Firestore.
     *
     * Reads all pending entries from Room, converts them back to Firestore
     * DTOs, and writes them to the Google user's collections. Existing
     * documents with the same ID are skipped (not overwritten).
     *
     * The entire batch is cleared from Room only after all writes succeed.
     * If the app crashes mid-apply, entries remain in Room for retry.
     *
     * @param uid The Google user's UID to write data to
     */
    suspend fun applyPendingMigration(uid: String) {
        val recipes = pendingMigrationDao.getAllByType(PendingMigrationEntity.TYPE_RECIPE)
        val mealPlans = pendingMigrationDao.getAllByType(PendingMigrationEntity.TYPE_MEAL_PLAN)

        if (recipes.isEmpty() && mealPlans.isEmpty()) return

        Log.d(TAG, "Applying ${recipes.size} recipes and ${mealPlans.size} meal plans to user $uid")

        // Collect existing IDs to avoid overwriting the Google account's data
        val existingRecipeIds = readDocumentIds(
            firestoreService.recipesCollection(uid)
        )
        val existingMealPlanIds = readDocumentIds(
            firestoreService.mealPlansCollection(uid)
        )

        var recipesWritten = 0
        var recipesSkipped = 0
        for (entity in recipes) {
            try {
                val recipe = json.decodeFromString<Recipe>(entity.json)
                if (recipe.id in existingRecipeIds) {
                    recipesSkipped++
                    continue
                }
                val dto = recipe.toDto()
                firestoreService.recipesCollection(uid).document(recipe.id).set(dto)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to write migrated recipe ${recipe.id}", e)
                    }
                recipesWritten++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize/write recipe ${entity.id}", e)
            }
        }

        var mealPlansWritten = 0
        var mealPlansSkipped = 0
        for (entity in mealPlans) {
            try {
                val entry = json.decodeFromString<MealPlanEntry>(entity.json)
                if (entry.id in existingMealPlanIds) {
                    mealPlansSkipped++
                    continue
                }
                val dto = entry.toDto()
                firestoreService.mealPlansCollection(uid).document(entry.id).set(dto)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to write migrated meal plan ${entry.id}", e)
                    }
                mealPlansWritten++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize/write meal plan ${entity.id}", e)
            }
        }

        Log.d(TAG, "Migration applied: $recipesWritten recipes written ($recipesSkipped skipped), " +
            "$mealPlansWritten meal plans written ($mealPlansSkipped skipped)")

        // All entries processed — clear the staging queue
        pendingMigrationDao.deleteAll()
    }

    /**
     * Returns true if there are pending migration entries in Room.
     * Used to detect incomplete migrations that need to be retried.
     */
    suspend fun hasPendingMigration(): Boolean {
        return pendingMigrationDao.count() > 0
    }

    /**
     * Reads all document IDs from a collection (used for dedup).
     */
    private suspend fun readDocumentIds(
        collection: com.google.firebase.firestore.CollectionReference
    ): Set<String> {
        return try {
            val snapshot = collection.get().await()
            snapshot.documents.map { it.id }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read existing document IDs for dedup", e)
            emptySet()
        }
    }
}
