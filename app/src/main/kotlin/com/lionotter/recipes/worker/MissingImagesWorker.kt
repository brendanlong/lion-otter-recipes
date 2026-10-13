package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.usecase.GenerateMissingImagesUseCase
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.ImageGenerationFailures.error
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Generates images, one at a time, for every recipe without one (Settings' "Generate images for
 * existing recipes"). It reads the list itself, so a stopped run picks up where it left off, and
 * a recipe that got an image meanwhile (or turning image generation off) is skipped. A key or
 * account problem stops it; other failures are counted and reported once at the end.
 */
@HiltWorker
class MissingImagesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val generateMissingImages: GenerateMissingImagesUseCase,
    private val generateRecipeImage: GenerateRecipeImageUseCase,
    notificationHelper: RecipeNotificationHelper,
    runningWork: RunningAccountWork
) : BaseRecipeWorker(context, workerParams, notificationHelper, runningWork) {

    override val notificationTitle: String get() = applicationContext.getString(R.string.creating_recipe_images)
    override val errorTitle: String get() = applicationContext.getString(R.string.image_generation_failed_title)
    override val operationKey: String = UNIQUE_WORK_NAME

    companion object {
        private const val TAG = "MissingImagesWorker"
        const val TAG_MISSING_IMAGES = "missing_images"
        private const val UNIQUE_WORK_NAME = "recipe-images-missing"
        private const val ATTEMPTS_PER_IMAGE = 3
        private val FIRST_RETRY_DELAY = 30.seconds

        internal suspend fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<MissingImagesWorker>()
                .addTag(TAG_MISSING_IMAGES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
        }
    }

    override suspend fun doRecipeWork(): Result {
        val recipeIds = generateMissingImages.recipesWithoutImages()
        var failedCount = 0
        for ((index, recipeId) in recipeIds.withIndex()) {
            setForegroundProgress(
                applicationContext.getString(R.string.creating_recipe_image_progress, index + 1, recipeIds.size)
            )
            val result = generateWithRetries(recipeId)
            val error = result.error() ?: continue
            ErrorLog.w(TAG, "Image generation failed: ${error.logDescription()}")
            if (ImageGenerationFailures.isAccountProblem(error)) {
                cancelProgressNotification()
                val resources = applicationContext.resources
                notificationHelper.showErrorNotification(
                    key = ImageGenerationFailures.ACCOUNT_PROBLEM_NOTIFICATION_KEY,
                    title = errorTitle,
                    errorMessage = ImageGenerationFailures.message(resources, result)
                )
                return Result.success()
            }
            failedCount++
        }
        cancelProgressNotification()
        if (failedCount > 0) {
            notificationHelper.showErrorNotification(
                key = operationKey,
                title = errorTitle,
                errorMessage = applicationContext.resources.getQuantityString(
                    R.plurals.missing_images_failed,
                    failedCount,
                    failedCount
                )
            )
        }
        return Result.success()
    }

    private suspend fun generateWithRetries(recipeId: String): GenerateRecipeImageUseCase.Result {
        var retryDelay = FIRST_RETRY_DELAY
        repeat(ATTEMPTS_PER_IMAGE - 1) {
            val result = generateOnce(recipeId)
            if (result.error()?.retryable != true) return result
            Log.w(TAG, "Image generation for $recipeId failed, retrying in $retryDelay: ${result.error()?.logDescription()}")
            delay(retryDelay)
            retryDelay *= 2
        }
        return generateOnce(recipeId)
    }

    private suspend fun generateOnce(recipeId: String): GenerateRecipeImageUseCase.Result =
        generateRecipeImage.execute(recipeId, prompt = null, replaceImage = false)
}
