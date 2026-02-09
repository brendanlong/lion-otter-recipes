package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importRecipeUseCase: ImportRecipeUseCase,
    private val notificationHelper: RecipeNotificationHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG_RECIPE_IMPORT = "recipe_import"

        const val KEY_URL = "url"
        const val KEY_IMPORT_ID = "import_id"
        const val KEY_RECIPE_ID = "recipe_id"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_TYPE = "result_type"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_FETCHING = "fetching"
        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SAVING = "saving"

        fun createInputData(url: String, importId: String): Data {
            return workDataOf(
                KEY_URL to url,
                KEY_IMPORT_ID to importId
            )
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No URL provided"
                )
            )
        val importId = inputData.getString(KEY_IMPORT_ID) ?: id.toString()

        // Set as foreground service for long-running work
        setForeground(notificationHelper.createForegroundInfo("Importing Recipe", "Starting import..."))

        val result = importRecipeUseCase.execute(
            url = url,
            onProgress = { progress ->
                val progressMessage = when (progress) {
                    is ImportRecipeUseCase.ImportProgress.FetchingPage -> {
                        setProgress(workDataOf(
                            KEY_IMPORT_ID to importId,
                            KEY_PROGRESS to PROGRESS_FETCHING
                        ))
                        "Fetching recipe page..."
                    }
                    is ImportRecipeUseCase.ImportProgress.ParsingRecipe -> {
                        setProgress(workDataOf(
                            KEY_IMPORT_ID to importId,
                            KEY_PROGRESS to PROGRESS_PARSING
                        ))
                        "AI is analyzing the recipe..."
                    }
                    is ImportRecipeUseCase.ImportProgress.RecipeNameAvailable -> {
                        setProgress(workDataOf(
                            KEY_IMPORT_ID to importId,
                            KEY_PROGRESS to PROGRESS_PARSING,
                            KEY_RECIPE_NAME to progress.name
                        ))
                        "AI is analyzing the recipe..."
                    }
                    is ImportRecipeUseCase.ImportProgress.SavingRecipe -> {
                        setProgress(workDataOf(
                            KEY_IMPORT_ID to importId,
                            KEY_PROGRESS to PROGRESS_SAVING
                        ))
                        "Saving recipe..."
                    }
                    is ImportRecipeUseCase.ImportProgress.Complete -> "Complete!"
                }
                setForeground(notificationHelper.createForegroundInfo("Importing Recipe", progressMessage))
            }
        )

        return when (result) {
            is ImportRecipeUseCase.ImportResult.Success -> {
                notificationHelper.showSuccessNotification(
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id
                )
                Result.success(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_RECIPE_ID to result.recipe.id,
                        KEY_RECIPE_NAME to result.recipe.name
                    )
                )
            }
            is ImportRecipeUseCase.ImportResult.Error -> {
                notificationHelper.showErrorNotification("Import Failed", result.message)
                Result.failure(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_RESULT_TYPE to RESULT_ERROR,
                        KEY_ERROR_MESSAGE to result.message
                    )
                )
            }
            ImportRecipeUseCase.ImportResult.NoApiKey -> {
                notificationHelper.cancelProgressNotification()
                Result.failure(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_RESULT_TYPE to RESULT_NO_API_KEY,
                        KEY_ERROR_MESSAGE to "API key not configured"
                    )
                )
            }
        }
    }
}
