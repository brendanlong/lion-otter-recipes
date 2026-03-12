package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Worker that imports a single recipe from a text file (.md, .txt, .html).
 *
 * - For .html files: uses [ParseHtmlUseCase.parseHtml] to extract readable content
 *   via Readability4J before sending to AI (same pipeline as URL imports).
 * - For .md and .txt files: uses [ParseHtmlUseCase.parseText] to send the raw content
 *   directly to the AI.
 */
@HiltWorker
class TextFileImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing Recipe from File"

    companion object {
        private const val TAG = "TextFileImportWorker"
        const val TAG_TEXT_FILE_IMPORT = "text_file_import"

        const val KEY_FILE_URI = "file_uri"
        const val KEY_FILE_TYPE = "file_type"
        const val KEY_IMPORT_ID = "import_id"
        const val KEY_RECIPE_ID = "recipe_id"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_TYPE = "result_type"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_READING_FILE = "reading_file"
        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SAVING = "saving"

        const val FILE_TYPE_HTML = "html"
        const val FILE_TYPE_MARKDOWN = "markdown"
        const val FILE_TYPE_TEXT = "text"

        fun createInputData(fileUri: String, fileType: String, importId: String): Data {
            return workDataOf(
                KEY_FILE_URI to fileUri,
                KEY_FILE_TYPE to fileType,
                KEY_IMPORT_ID to importId
            )
        }
    }

    override suspend fun doWork(): Result {
        val fileUriString = inputData.getString(KEY_FILE_URI)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No file URI provided"
                )
            )
        val fileType = inputData.getString(KEY_FILE_TYPE) ?: FILE_TYPE_TEXT
        val importId = inputData.getString(KEY_IMPORT_ID) ?: id.toString()

        setForegroundProgress("Reading file...")
        setProgress(
            workDataOf(
                KEY_IMPORT_ID to importId,
                KEY_PROGRESS to PROGRESS_READING_FILE
            )
        )

        // Read file content
        val fileUri = fileUriString.toUri()
        val content = try {
            context.contentResolver.openInputStream(fileUri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file", e)
            return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "Failed to read file: ${e.message}"
                )
            )
        }

        if (content.isNullOrBlank()) {
            return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "File is empty"
                )
            )
        }

        setForegroundProgress("AI is analyzing the recipe...")

        // Parse with AI using the appropriate method
        val parseResult = when (fileType) {
            FILE_TYPE_HTML -> parseHtmlUseCase.parseHtml(
                html = content,
                saveRecipe = true,
                onProgress = { progress -> handleProgress(progress, importId) }
            )
            else -> parseHtmlUseCase.parseText(
                text = content,
                saveRecipe = true,
                onProgress = { progress -> handleProgress(progress, importId) }
            )
        }

        return when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success -> {
                withContext(NonCancellable) {
                    notificationHelper.showSuccessNotification(
                        recipeName = parseResult.recipe.name,
                        recipeId = parseResult.recipe.id
                    )
                }
                Result.success(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_RECIPE_ID to parseResult.recipe.id,
                        KEY_RECIPE_NAME to parseResult.recipe.name
                    )
                )
            }
            is ParseHtmlUseCase.ParseResult.Error -> errorResult(
                errorNotificationTitle = "Import Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = parseResult.message,
                KEY_IMPORT_ID to importId
            )
            ParseHtmlUseCase.ParseResult.NoApiKey -> notAvailableResult(
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                resultType = RESULT_NO_API_KEY,
                errorMessage = "API key not configured",
                KEY_IMPORT_ID to importId
            )
        }
    }

    private suspend fun handleProgress(progress: ParseHtmlUseCase.ParseProgress, importId: String) {
        when (progress) {
            is ParseHtmlUseCase.ParseProgress.ExtractingContent -> {
                setProgress(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_PROGRESS to PROGRESS_READING_FILE
                    )
                )
                setForegroundProgress("Extracting content...")
            }
            is ParseHtmlUseCase.ParseProgress.ParsingRecipe -> {
                setProgress(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_PROGRESS to PROGRESS_PARSING
                    )
                )
                setForegroundProgress("AI is analyzing the recipe...")
            }
            is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable -> {
                setProgress(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_PROGRESS to PROGRESS_PARSING,
                        KEY_RECIPE_NAME to progress.name
                    )
                )
                setForegroundProgress("Parsing: ${progress.name}")
            }
            is ParseHtmlUseCase.ParseProgress.SavingRecipe -> {
                setProgress(
                    workDataOf(
                        KEY_IMPORT_ID to importId,
                        KEY_PROGRESS to PROGRESS_SAVING
                    )
                )
                setForegroundProgress("Saving recipe...")
            }
            is ParseHtmlUseCase.ParseProgress.Complete -> {
                // Handled by return value
            }
        }
    }
}
