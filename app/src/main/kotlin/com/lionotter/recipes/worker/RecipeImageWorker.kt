package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.AccountLeftException
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.ImageGenerationFailures.error
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

/**
 * Generates an image for one recipe ([GenerateRecipeImageUseCase]): for a new import, or when the
 * user asks for one. These run one at a time in a WorkManager chain. Transient failures are
 * retried. A final failure is shown as a notification; a key or account problem also fails the
 * work, which stops the rest of the chain (they would all fail the same way), while any other
 * failure ends it successfully so the chain goes on.
 */
@HiltWorker
class RecipeImageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val generateRecipeImage: GenerateRecipeImageUseCase,
    private val notificationHelper: RecipeNotificationHelper,
    private val runningWork: RunningAccountWork
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "RecipeImageWorker"
        const val TAG_RECIPE_IMAGE = "recipe_image"
        // Persisted by WorkManager: never change them
        internal const val KEY_RECIPE_ID = "recipe_id"
        internal const val KEY_PROMPT = "prompt"
        internal const val KEY_REPLACE_IMAGE = "replace_image"
        internal const val MAX_ATTEMPTS = 3
        private const val QUEUE_NAME = "recipe-images"

        fun createInputData(recipeId: String, prompt: String?, replaceImage: Boolean): Data = workDataOf(
            KEY_RECIPE_ID to recipeId,
            KEY_PROMPT to prompt?.take(ResourceLimits.MAX_IMAGE_PROMPT_CHARS),
            KEY_REPLACE_IMAGE to replaceImage
        )

        internal suspend fun enqueue(workManager: WorkManager, recipeId: String, prompt: String?, replaceImage: Boolean) {
            val request = OneTimeWorkRequestBuilder<RecipeImageWorker>()
                .setInputData(createInputData(recipeId, prompt, replaceImage))
                .addTag(TAG_RECIPE_IMAGE)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // A chain whose last work failed or was cancelled is replaced by a new one
            workManager.enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
        }
    }

    override suspend fun doWork(): Result = runningWork.track {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
        if (recipeId == null) {
            ErrorLog.e(TAG, "No recipe to generate an image for")
            return@track Result.success()
        }
        val prompt = inputData.getString(KEY_PROMPT)
        val replaceImage = inputData.getBoolean(KEY_REPLACE_IMAGE, false)
        // Never put output data in a success: chained work gets it merged into its input
        try {
            val result = generateRecipeImage.execute(recipeId, prompt, replaceImage)
            val error = result.error()
            if (error == null) {
                Log.d(TAG, "Image generation for recipe $recipeId: $result")
                Result.success()
            } else {
                failed(recipeId, result, error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountLeftException) {
            // Signing out cancels the whole chain anyway
            Log.w(TAG, "Image generation stopped: the account was signed out", e)
            Result.failure()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Image generation failed unexpectedly", e)
            val unexpected = GenerateRecipeImageUseCase.Result.ImageFailed(RecipeError.Unexpected)
            failed(recipeId, unexpected, RecipeError.Unexpected)
        }
    }

    private fun failed(recipeId: String, result: GenerateRecipeImageUseCase.Result, error: RecipeError): Result {
        if (error.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
            Log.w(TAG, "Image generation attempt ${runAttemptCount + 1} failed, will retry: ${error.logDescription()}")
            return Result.retry()
        }
        ErrorLog.w(TAG, "Image generation failed: ${error.logDescription()}")
        val accountProblem = ImageGenerationFailures.isAccountProblem(error)
        val resources = applicationContext.resources
        notificationHelper.showErrorNotification(
            key = if (accountProblem) ImageGenerationFailures.ACCOUNT_PROBLEM_NOTIFICATION_KEY else "recipe-image-$recipeId",
            title = resources.getString(R.string.image_generation_failed_title),
            errorMessage = ImageGenerationFailures.message(resources, result)
        )
        return if (accountProblem) Result.failure() else Result.success()
    }
}

/** [RecipeImageScheduler] backed by [RecipeImageWorker] and [MissingImagesWorker]. */
class WorkManagerRecipeImageScheduler @Inject constructor(
    // Lazy: WorkManager initialization needs the Hilt worker factory, which (indirectly) depends on this.
    private val workManager: Provider<WorkManager>
) : RecipeImageScheduler {
    override suspend fun scheduleImageGeneration(recipeId: String, prompt: String?, replaceImage: Boolean) {
        RecipeImageWorker.enqueue(workManager.get(), recipeId, prompt, replaceImage)
    }

    override suspend fun scheduleMissingImages() {
        MissingImagesWorker.enqueue(workManager.get())
    }
}
