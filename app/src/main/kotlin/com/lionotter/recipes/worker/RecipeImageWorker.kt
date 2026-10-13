package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
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
import androidx.work.workDataOf
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.AccountLeftException
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

/**
 * Generates the image for a recipe imported without one ([GenerateRecipeImageUseCase]).
 * Transient failures are retried; a final failure is shown as a notification, since the recipe
 * itself was already saved.
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
        internal const val MAX_ATTEMPTS = 3

        /** One notification for a problem with the key or account, not one per recipe. */
        private const val ACCOUNT_PROBLEM_NOTIFICATION_KEY = "recipe-image-account"

        /** Keeps the input well within WorkManager's 10 KB `Data` limit; the AI is asked for far less. */
        private const val MAX_PROMPT_LENGTH = 2000

        fun createInputData(recipeId: String, prompt: String): Data =
            workDataOf(KEY_RECIPE_ID to recipeId, KEY_PROMPT to prompt.take(MAX_PROMPT_LENGTH))

        internal fun enqueue(workManager: WorkManager, recipeId: String, prompt: String) {
            val request = OneTimeWorkRequestBuilder<RecipeImageWorker>()
                .setInputData(createInputData(recipeId, prompt))
                .addTag(TAG_RECIPE_IMAGE)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork("recipe-image-$recipeId", ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result = runningWork.track {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
        val prompt = inputData.getString(KEY_PROMPT)
        if (recipeId == null || prompt.isNullOrBlank()) {
            ErrorLog.e(TAG, "No recipe or prompt to generate an image for")
            return@track Result.failure()
        }
        try {
            when (val result = generateRecipeImage.execute(recipeId, prompt)) {
                is Outcome.Success -> {
                    Log.d(TAG, "Image generation for recipe $recipeId: ${result.value}")
                    Result.success()
                }
                is Outcome.Failure -> failed(recipeId, result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountLeftException) {
            Log.w(TAG, "Image generation stopped: the account was signed out", e)
            Result.failure()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Image generation failed unexpectedly", e)
            failed(recipeId, RecipeError.Unexpected)
        }
    }

    private fun failed(recipeId: String, error: RecipeError): Result {
        if (error.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
            Log.w(TAG, "Image generation attempt ${runAttemptCount + 1} failed, will retry: ${error.logDescription()}")
            return Result.retry()
        }
        ErrorLog.w(TAG, "Image generation failed: ${error.logDescription()}")
        val accountProblem = accountProblemMessage(error)
        val resources = applicationContext.resources
        notificationHelper.showErrorNotification(
            key = if (accountProblem != null) ACCOUNT_PROBLEM_NOTIFICATION_KEY else "recipe-image-$recipeId",
            title = resources.getString(R.string.image_generation_failed_title),
            errorMessage = resources.getString(accountProblem ?: recipeProblemMessage(error))
        )
        return Result.failure()
    }

    @StringRes
    private fun accountProblemMessage(error: RecipeError): Int? = when ((error as? RecipeError.Ai)?.reason) {
        AiFailure.INVALID_API_KEY -> R.string.image_generation_invalid_key
        AiFailure.PERMISSION_DENIED -> R.string.image_generation_permission_denied
        AiFailure.OUT_OF_CREDITS -> R.string.image_generation_out_of_credits
        else -> null
    }

    @StringRes
    private fun recipeProblemMessage(error: RecipeError): Int =
        if ((error as? RecipeError.Ai)?.reason == AiFailure.REFUSED) {
            R.string.image_generation_refused
        } else {
            R.string.image_generation_failed
        }
}

/** [RecipeImageScheduler] backed by [RecipeImageWorker]. */
class WorkManagerRecipeImageScheduler @Inject constructor(
    // Lazy: WorkManager initialization needs the Hilt worker factory, which (indirectly) depends on this.
    private val workManager: Provider<WorkManager>
) : RecipeImageScheduler {
    override fun scheduleImageGeneration(recipeId: String, prompt: String) {
        RecipeImageWorker.enqueue(workManager.get(), recipeId, prompt)
    }
}
