package com.lionotter.recipes.data.image

import android.util.Log
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads recipe images that are still only on this device (`file://`: added as a guest, or
 * whose upload failed) once the user syncs, and points their recipes and meal plans at the
 * uploads. Run by `LocalImageUploadWorker` (see [ImageUploadScheduler]).
 */
@Singleton
class LocalImageUploader @Inject constructor(
    private val localFiles: LocalImageFiles,
    private val remoteStorage: RemoteImageStorage,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val authService: AuthService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LocalImageUploader"
    }

    data class Result(val uploaded: Int, val failed: Int)

    private val mutex = Mutex()

    /**
     * Uploads every recipe image that is still a local `file://` URL. Idempotent and safe to call
     * repeatedly; runs to completion even if the caller is cancelled. A failed upload keeps the
     * local file (and counts as failed, so the caller retries).
     *
     * A recipe is only pointed at the upload if its image is still the local file, so a newer
     * image picked meanwhile is never reverted; the unused upload is then deleted.
     *
     * @throws Exception if the recipes couldn't be read
     */
    suspend fun uploadPendingLocalImages(): Result = withContext(NonCancellable + ioDispatcher) {
        mutex.withLock {
            // Waits out an auth transition (e.g. the sign-in that scheduled this pass)
            if (authService.authState.first { it !is AuthState.Loading } !is AuthState.Google) {
                Log.d(TAG, "Not signed in with Google; skipping local image upload")
                return@withLock Result(uploaded = 0, failed = 0)
            }
            var uploaded = 0
            var failed = 0
            for ((recipeId, localUri) in recipeRepository.getImageUrlsByRecipeId()) {
                if (!LocalImageFiles.isLocalUrl(localUri)) continue
                val file = localFiles.file(localUri)
                if (file == null) {
                    Log.w(TAG, "Local image for recipe $recipeId no longer exists; nothing to upload")
                    continue
                }
                val storageUrl = remoteStorage.upload(file)
                if (storageUrl == null) {
                    Log.w(TAG, "Could not upload local image for recipe $recipeId")
                    failed++
                    continue
                }
                try {
                    if (recipeRepository.replaceImageUrl(recipeId, expected = localUri, replacement = storageUrl)) {
                        updateMealPlans(localUri, storageUrl)
                        uploaded++
                    } else {
                        Log.i(TAG, "Recipe $recipeId image changed during upload; discarding the upload")
                        if (!isReferenced(storageUrl)) remoteStorage.delete(storageUrl)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to point recipe $recipeId at its uploaded image", e)
                    failed++
                }
            }
            Log.i(TAG, "Uploaded $uploaded local images ($failed failed)")
            Result(uploaded = uploaded, failed = failed)
        }
    }

    /**
     * True if a Google user has recipes whose image is only on this device (a `file://` that
     * exists here), i.e. would be lost by clearing local data. True if this can't be determined.
     */
    suspend fun hasPendingLocalImages(): Boolean {
        if (!authService.isGoogleUser()) return false
        return try {
            recipeRepository.getImageUrlsByRecipeId(localOnly = true).values.any { localFiles.exists(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check for images pending upload", e)
            true
        }
    }

    private suspend fun updateMealPlans(localUri: String, storageUrl: String) {
        try {
            mealPlanRepository.replaceRecipeImageUrl(localUri, storageUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update meal plan images for $localUri", e)
        }
    }

    private suspend fun isReferenced(storageUrl: String): Boolean = try {
        recipeRepository.isImageReferenced(storageUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not check whether $storageUrl is in use; keeping it", e)
        true
    }
}
