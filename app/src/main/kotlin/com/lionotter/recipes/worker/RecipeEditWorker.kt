package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.EditRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeEditWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val editRecipeUseCase: EditRecipeUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Updating Recipe"

    companion object {
        const val TAG_RECIPE_EDIT = "recipe_edit"

        const val KEY_RECIPE_ID = "recipe_id"
        const val KEY_MARKDOWN_TEXT = "markdown_text"
        const val KEY_MODEL = "model"
        const val KEY_EXTENDED_THINKING = "extended_thinking"
        const val KEY_SAVE_AS_COPY = "save_as_copy"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_NEW_RECIPE_ID = "new_recipe_id"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SAVING = "saving"

        fun createInputData(
            recipeId: String,
            markdownText: String,
            model: String?,
            thinkingEnabled: Boolean?,
            saveAsCopy: Boolean = false
        ): Data {
            return workDataOf(
                KEY_RECIPE_ID to recipeId,
                KEY_MARKDOWN_TEXT to markdownText,
                KEY_MODEL to model,
                KEY_EXTENDED_THINKING to thinkingEnabled,
                KEY_SAVE_AS_COPY to saveAsCopy
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
        val markdownText = inputData.getString(KEY_MARKDOWN_TEXT)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No markdown text provided"
                )
            )
        val model = inputData.getString(KEY_MODEL)
        val thinkingEnabled = if (inputData.keyValueMap.containsKey(KEY_EXTENDED_THINKING)) {
            inputData.getBoolean(KEY_EXTENDED_THINKING, true)
        } else {
            null
        }
        val saveAsCopy = inputData.getBoolean(KEY_SAVE_AS_COPY, false)

        setForegroundProgress(if (saveAsCopy) "Saving copy..." else "Updating recipe...")

        val result = editRecipeUseCase.execute(
            recipeId = recipeId,
            markdownText = markdownText,
            saveAsCopy = saveAsCopy,
            model = model,
            thinkingEnabled = thinkingEnabled,
            onProgress = { progress ->
                val progressMessage = when (progress) {
                    is EditRecipeUseCase.EditProgress.ParsingRecipe -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_PARSING
                        ))
                        "AI is processing your changes..."
                    }
                    is EditRecipeUseCase.EditProgress.RecipeNameAvailable -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_PARSING,
                            KEY_RECIPE_NAME to progress.name
                        ))
                        "AI is processing your changes..."
                    }
                    is EditRecipeUseCase.EditProgress.SavingRecipe -> {
                        setProgress(workDataOf(
                            KEY_RECIPE_ID to recipeId,
                            KEY_PROGRESS to PROGRESS_SAVING
                        ))
                        "Saving recipe..."
                    }
                    is EditRecipeUseCase.EditProgress.Complete -> "Complete!"
                }
                setForegroundProgress(progressMessage)
            }
        )

        return when (result) {
            is EditRecipeUseCase.EditResult.Success -> {
                notificationHelper.showSuccessNotification(
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id
                )
                Result.success(
                    workDataOf(
                        KEY_RECIPE_ID to recipeId,
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_RECIPE_NAME to result.recipe.name,
                        KEY_NEW_RECIPE_ID to if (saveAsCopy) result.recipe.id else null
                    )
                )
            }
            is EditRecipeUseCase.EditResult.Error -> errorResult(
                errorNotificationTitle = "Edit Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message,
                KEY_RECIPE_ID to recipeId
            )
            EditRecipeUseCase.EditResult.NoApiKey -> notAvailableResult(
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                resultType = RESULT_NO_API_KEY,
                errorMessage = "API key not configured",
                KEY_RECIPE_ID to recipeId
            )
        }
    }
}
