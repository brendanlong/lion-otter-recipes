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
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

/**
 * Generates an image for a recipe ([GenerateRecipeImageUseCase]). All of these run one at a time
 * in a single WorkManager chain, so filling in many missing images doesn't hit OpenAI's rate
 * limits. Transient failures are retried; a final failure is shown as a notification and still
 * ends the work successfully, so the rest of the chain runs.
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

        /** One notification for a problem with a key or account, not one per recipe. */
        private const val ACCOUNT_PROBLEM_NOTIFICATION_KEY = "recipe-image-account"
        private val ACCOUNT_FAILURES = setOf(AiFailure.INVALID_API_KEY, AiFailure.PERMISSION_DENIED, AiFailure.OUT_OF_CREDITS)

        /** Keeps the input well within WorkManager's 10 KB `Data` limit; the AI is asked for far less. */
        private const val MAX_PROMPT_LENGTH = 2000

        fun createInputData(recipeId: String, prompt: String?, replaceImage: Boolean): Data = workDataOf(
            KEY_RECIPE_ID to recipeId,
            KEY_PROMPT to prompt?.take(MAX_PROMPT_LENGTH),
            KEY_REPLACE_IMAGE to replaceImage
        )

        internal fun enqueue(workManager: WorkManager, recipeIds: List<String>, prompt: String?, replaceImage: Boolean) {
            val requests = recipeIds.map { recipeId ->
                OneTimeWorkRequestBuilder<RecipeImageWorker>()
                    .setInputData(createInputData(recipeId, prompt, replaceImage))
                    .addTag(TAG_RECIPE_IMAGE)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            }
            if (requests.isEmpty()) return
            var chain = workManager.beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
            for (request in requests.drop(1)) chain = chain.then(request)
            chain.enqueue()
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
        try {
            when (val result = generateRecipeImage.execute(recipeId, prompt, replaceImage)) {
                is GenerateRecipeImageUseCase.Result.Generated, GenerateRecipeImageUseCase.Result.Skipped -> {
                    Log.d(TAG, "Image generation for recipe $recipeId: $result")
                    Result.success()
                }
                is GenerateRecipeImageUseCase.Result.PromptFailed -> failed(recipeId, result.error, promptFailed = true)
                is GenerateRecipeImageUseCase.Result.ImageFailed -> failed(recipeId, result.error, promptFailed = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountLeftException) {
            Log.w(TAG, "Image generation stopped: the account was signed out", e)
            Result.failure()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Image generation failed unexpectedly", e)
            failed(recipeId, RecipeError.Unexpected, promptFailed = false)
        }
    }

    /** Retries [error] if it is transient; otherwise notifies and lets the rest of the queue run. */
    private fun failed(recipeId: String, error: RecipeError, promptFailed: Boolean): Result {
        if (error.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
            Log.w(TAG, "Image generation attempt ${runAttemptCount + 1} failed, will retry: ${error.logDescription()}")
            return Result.retry()
        }
        ErrorLog.w(TAG, "Image generation failed: ${error.logDescription()}")
        val resources = applicationContext.resources
        val accountProblem = error.isAccountProblem()
        val message = when {
            error == RecipeError.NoApiKey -> resources.getString(R.string.image_generation_no_anthropic_key)
            // Anthropic's messages, which say which key or account to check
            promptFailed -> error.toUserMessage().resolve(resources)
            else -> resources.getString(imageFailureMessage(error))
        }
        notificationHelper.showErrorNotification(
            key = if (accountProblem) ACCOUNT_PROBLEM_NOTIFICATION_KEY else "recipe-image-$recipeId",
            title = resources.getString(R.string.image_generation_failed_title),
            errorMessage = message
        )
        return Result.success()
    }

    /** A problem with a key or account, which every queued image would hit again. */
    private fun RecipeError.isAccountProblem(): Boolean =
        this == RecipeError.NoApiKey || (this is RecipeError.Ai && reason in ACCOUNT_FAILURES)

    @StringRes
    private fun imageFailureMessage(error: RecipeError): Int = when ((error as? RecipeError.Ai)?.reason) {
        AiFailure.INVALID_API_KEY -> R.string.image_generation_invalid_key
        AiFailure.PERMISSION_DENIED -> R.string.image_generation_permission_denied
        AiFailure.OUT_OF_CREDITS -> R.string.image_generation_out_of_credits
        AiFailure.REFUSED -> R.string.image_generation_refused
        else -> R.string.image_generation_failed
    }
}

/** [RecipeImageScheduler] backed by [RecipeImageWorker]. */
class WorkManagerRecipeImageScheduler @Inject constructor(
    // Lazy: WorkManager initialization needs the Hilt worker factory, which (indirectly) depends on this.
    private val workManager: Provider<WorkManager>
) : RecipeImageScheduler {
    override fun scheduleImageGeneration(recipeId: String, prompt: String?, replaceImage: Boolean) {
        RecipeImageWorker.enqueue(workManager.get(), listOf(recipeId), prompt, replaceImage)
    }

    override fun scheduleMissingImages(recipeIds: List<String>) {
        RecipeImageWorker.enqueue(workManager.get(), recipeIds, prompt = null, replaceImage = false)
    }
}
