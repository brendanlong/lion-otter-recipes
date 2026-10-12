package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.usecase.EditRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeEditWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val editRecipeUseCase: EditRecipeUseCase,
    private val inputStore: ImportInputStore,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Updating Recipe"
    override val errorTitle = "Edit Failed"
    override val operationKey: String get() = inputData.getString(KEY_RECIPE_ID) ?: id.toString()

    companion object {
        const val TAG_RECIPE_EDIT = "recipe_edit"

        const val KEY_RECIPE_ID = "recipe_id"
        /** Path to a file holding the markdown (it can exceed WorkManager's 10 KB Data limit). */
        const val KEY_MARKDOWN_PATH = "markdown_path"
        const val KEY_MODEL = "model"
        const val KEY_EXTENDED_THINKING = "extended_thinking"
        const val KEY_SAVE_AS_COPY = "save_as_copy"
        const val KEY_AI_INSTRUCTIONS = "ai_instructions"
        const val KEY_COPY_RECIPE_ID = "copy_recipe_id"
        const val KEY_ERROR_MESSAGE = BaseRecipeWorker.KEY_ERROR_MESSAGE
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_TYPE = BaseRecipeWorker.KEY_RESULT_TYPE
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_NEW_RECIPE_ID = "new_recipe_id"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = BaseRecipeWorker.RESULT_ERROR
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SAVING = "saving"

        fun createInputData(
            recipeId: String,
            markdownPath: String,
            model: String?,
            thinkingEnabled: Boolean?,
            saveAsCopy: Boolean = false,
            aiInstructions: String? = null,
            copyRecipeId: String? = null
        ): Data {
            return workDataOf(
                KEY_RECIPE_ID to recipeId,
                KEY_MARKDOWN_PATH to markdownPath,
                KEY_MODEL to model,
                KEY_EXTENDED_THINKING to thinkingEnabled,
                KEY_SAVE_AS_COPY to saveAsCopy,
                KEY_COPY_RECIPE_ID to copyRecipeId,
                // Instructions are user-typed; keep them well under the Data size limit.
                KEY_AI_INSTRUCTIONS to aiInstructions?.take(4000)
            )
        }
    }

    override suspend fun doRecipeWork(): Result {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
            ?: return errorResult("No recipe ID provided")
        val markdownPath = inputData.getString(KEY_MARKDOWN_PATH)
            ?: return errorResult("No markdown text provided")
        val markdownText = inputStore.readWorkText(markdownPath)
            ?: return errorResult("The edited recipe text was lost. Please try again.")
        val model = inputData.getString(KEY_MODEL)
        val thinkingEnabled = if (inputData.keyValueMap.containsKey(KEY_EXTENDED_THINKING)) {
            inputData.getBoolean(KEY_EXTENDED_THINKING, SettingsDataStore.DEFAULT_THINKING_ENABLED)
        } else {
            null
        }
        val saveAsCopy = inputData.getBoolean(KEY_SAVE_AS_COPY, false)
        val aiInstructions = inputData.getString(KEY_AI_INSTRUCTIONS)

        setForegroundProgress(if (saveAsCopy) "Saving copy..." else "Updating recipe...")

        val result = editRecipeUseCase.execute(
            recipeId = recipeId,
            markdownText = markdownText,
            saveAsCopy = saveAsCopy,
            copyRecipeId = inputData.getString(KEY_COPY_RECIPE_ID),
            model = model,
            thinkingEnabled = thinkingEnabled,
            aiInstructions = aiInstructions,
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

        val workResult = when (result) {
            is EditRecipeUseCase.EditResult.Success -> {
                cancelProgressNotification()
                notificationHelper.showSuccessNotification(
                    key = operationKey,
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id,
                    title = if (saveAsCopy) "Recipe Copy Saved" else "Recipe Updated"
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
                errorMessage = result.message,
                retryable = result.retryable,
                extraData = mapOf(KEY_RECIPE_ID to recipeId)
            )
            EditRecipeUseCase.EditResult.NoApiKey -> errorResult(
                errorMessage = "API key not configured",
                errorType = RESULT_NO_API_KEY,
                notify = false,
                extraData = mapOf(KEY_RECIPE_ID to recipeId)
            )
        }
        val retrying = result is EditRecipeUseCase.EditResult.Error && willRetry(result.retryable)
        if (!retrying) {
            inputStore.deleteWorkText(markdownPath)
        }
        return workResult
    }
}
