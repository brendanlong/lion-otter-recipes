package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.usecase.RegenerateRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeRegenerateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val regenerateRecipeUseCase: RegenerateRecipeUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Regenerating Recipe"
    override val errorTitle = "Regeneration Failed"
    override val operationKey: String get() = inputData.getString(KEY_RECIPE_ID) ?: id.toString()

    companion object {
        const val TAG_RECIPE_REGENERATE = "recipe_regenerate"

        private const val KEY_RECIPE_ID = WorkResult.KEY_RECIPE_ID
        private const val KEY_MODEL = "model"
        private const val KEY_EXTENDED_THINKING = "extended_thinking"

        fun createInputData(
            recipeId: String,
            model: String?,
            thinkingEnabled: Boolean?
        ): Data {
            return workDataOf(
                KEY_RECIPE_ID to recipeId,
                KEY_MODEL to model,
                KEY_EXTENDED_THINKING to thinkingEnabled
            )
        }
    }

    override suspend fun doRecipeWork(): Result {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
            ?: return errorResult("No recipe ID provided")
        val model = inputData.getString(KEY_MODEL)
        val thinkingEnabled = if (inputData.keyValueMap.containsKey(KEY_EXTENDED_THINKING)) {
            inputData.getBoolean(KEY_EXTENDED_THINKING, SettingsDataStore.DEFAULT_THINKING_ENABLED)
        } else {
            null
        }

        setForegroundProgress("Starting regeneration...")

        val result = regenerateRecipeUseCase.execute(
            recipeId = recipeId,
            model = model,
            thinkingEnabled = thinkingEnabled,
            onProgress = { progress ->
                val (workProgress, message) = when (progress) {
                    is RegenerateRecipeUseCase.RegenerateProgress.FetchingFromUrl ->
                        RecipeRegenerateWork.Progress.FetchingPage to "Fetching recipe page..."
                    is RegenerateRecipeUseCase.RegenerateProgress.ParsingRecipe ->
                        RecipeRegenerateWork.Progress.Parsing(recipeName = null) to "AI is re-analyzing the recipe..."
                    is RegenerateRecipeUseCase.RegenerateProgress.RecipeNameAvailable ->
                        RecipeRegenerateWork.Progress.Parsing(progress.name) to "AI is re-analyzing the recipe..."
                    is RegenerateRecipeUseCase.RegenerateProgress.SavingRecipe ->
                        RecipeRegenerateWork.Progress.Saving to "Saving recipe..."
                    is RegenerateRecipeUseCase.RegenerateProgress.Complete -> null to "Complete!"
                }
                workProgress?.let { setProgress(it.toData()) }
                setForegroundProgress(message)
            }
        )

        return when (result) {
            is RegenerateRecipeUseCase.RegenerateResult.Success -> {
                notificationHelper.showSuccessNotification(
                    key = operationKey,
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id,
                    title = "Recipe Regenerated"
                )
                successResult(RecipeRegenerateWork.Outcome.Success(recipeId, result.recipe.name))
            }
            is RegenerateRecipeUseCase.RegenerateResult.Error -> errorResult(
                RecipeRegenerateWork.Outcome.Failed(result.message, recipeId),
                retryable = result.retryable
            )
            RegenerateRecipeUseCase.RegenerateResult.NoApiKey -> errorResult(
                RecipeRegenerateWork.Outcome.NoApiKey("API key not configured", recipeId),
                notify = false
            )
            RegenerateRecipeUseCase.RegenerateResult.NoSourceUrl -> errorResult(
                RecipeRegenerateWork.Outcome.NoSourceUrl("No source URL available for this recipe", recipeId)
            )
        }
    }
}

object RecipeRegenerateWork : WorkContract<RecipeRegenerateWork.Progress, RecipeRegenerateWork.Outcome> {
    private const val PROGRESS_FETCHING = "fetching"
    private const val PROGRESS_PARSING = "parsing"
    private const val PROGRESS_SAVING = "saving"

    sealed interface Progress {
        fun toData(): Data

        data object FetchingPage : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_FETCHING)
        }

        data class Parsing(val recipeName: String?) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_PARSING,
                WorkResult.KEY_RECIPE_NAME to recipeName
            )
        }

        data object Saving : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_SAVING)
        }
    }

    sealed interface Outcome {
        data class Success(val recipeId: String?, val recipeName: String?) : Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_RECIPE_ID to recipeId,
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                WorkResult.KEY_RECIPE_NAME to recipeName
            )
        }

        data class NoApiKey(override val message: String?, val recipeId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.NO_API_KEY, message, WorkResult.KEY_RECIPE_ID to recipeId)
        }

        data class NoSourceUrl(override val message: String?, val recipeId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.NO_SOURCE_URL, message, WorkResult.KEY_RECIPE_ID to recipeId)
        }

        data class Failed(override val message: String?, val recipeId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.ERROR, message, WorkResult.KEY_RECIPE_ID to recipeId)
        }
    }

    override fun decodeProgress(data: Data): Progress? = when (WorkResult.progress(data)) {
        PROGRESS_FETCHING -> Progress.FetchingPage
        PROGRESS_PARSING -> Progress.Parsing(data.getString(WorkResult.KEY_RECIPE_NAME))
        PROGRESS_SAVING -> Progress.Saving
        else -> null
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome {
        val recipeId = data.getString(WorkResult.KEY_RECIPE_ID)
        val message = WorkResult.message(data)
        return when {
            succeeded -> Outcome.Success(recipeId, data.getString(WorkResult.KEY_RECIPE_NAME))
            else -> when (WorkResult.type(data)) {
                WorkResult.NO_API_KEY -> Outcome.NoApiKey(message, recipeId)
                WorkResult.NO_SOURCE_URL -> Outcome.NoSourceUrl(message, recipeId)
                else -> Outcome.Failed(message, recipeId)
            }
        }
    }
}
