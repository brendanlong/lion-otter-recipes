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
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val regenerateRecipeUseCase: RegenerateRecipeUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Regenerating Recipe"

    companion object {
        const val TAG_RECIPE_REGENERATE = "recipe_regenerate"

        const val KEY_RECIPE_ID = "recipe_id"
        const val KEY_MODEL = "model"
        const val KEY_EXTENDED_THINKING = "extended_thinking"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_RECIPE_NAME = "recipe_name"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NO_API_KEY = "no_api_key"
        const val RESULT_NO_ORIGINAL_HTML = "no_original_html"

        const val PROGRESS_FETCHING = "fetching"
        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SAVING = "saving"

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

    override suspend fun doWork(): Result {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No recipe ID provided"
                )
            )
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
                val progressMessage = when (progress) {
                    is RegenerateRecipeUseCase.RegenerateProgress.FetchingFromUrl -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_FETCHING
                        ))
                        "Fetching recipe page..."
                    }
                    is RegenerateRecipeUseCase.RegenerateProgress.ParsingRecipe -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_PARSING
                        ))
                        "AI is re-analyzing the recipe..."
                    }
                    is RegenerateRecipeUseCase.RegenerateProgress.RecipeNameAvailable -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_PARSING,
                            KEY_RECIPE_NAME to progress.name
                        ))
                        "AI is re-analyzing the recipe..."
                    }
                    is RegenerateRecipeUseCase.RegenerateProgress.SavingRecipe -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_SAVING
                        ))
                        "Saving recipe..."
                    }
                    is RegenerateRecipeUseCase.RegenerateProgress.Complete -> "Complete!"
                }
                setForegroundProgress(progressMessage)
            }
        )

        return when (result) {
            is RegenerateRecipeUseCase.RegenerateResult.Success -> {
                notificationHelper.showSuccessNotification(
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id
                )
                Result.success(
                    workDataOf(
                        KEY_RECIPE_ID to recipeId,
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_RECIPE_NAME to result.recipe.name
                    )
                )
            }
            is RegenerateRecipeUseCase.RegenerateResult.Error -> errorResult(
                errorNotificationTitle = "Regeneration Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message,
                KEY_RECIPE_ID to recipeId
            )
            RegenerateRecipeUseCase.RegenerateResult.NoApiKey -> notAvailableResult(
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                resultType = RESULT_NO_API_KEY,
                errorMessage = "API key not configured",
                KEY_RECIPE_ID to recipeId
            )
            RegenerateRecipeUseCase.RegenerateResult.NoOriginalHtml -> errorResult(
                errorNotificationTitle = "Regeneration Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_NO_ORIGINAL_HTML,
                errorMessage = "Original HTML not available for this recipe",
                KEY_RECIPE_ID to recipeId
            )
        }
    }
}
