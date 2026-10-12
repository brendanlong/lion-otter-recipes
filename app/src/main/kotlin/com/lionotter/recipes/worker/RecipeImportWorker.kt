package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importRecipeUseCase: ImportRecipeUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing Recipe"
    override val errorTitle = "Import Failed"

    companion object {
        const val TAG_RECIPE_IMPORT = "recipe_import"

        private const val KEY_URL = "url"

        fun createInputData(url: String, importId: String): Data = workDataOf(
            KEY_URL to url,
            KEY_IMPORT_ID to importId
        )
    }

    override suspend fun doRecipeWork(): Result {
        val url = inputData.getString(KEY_URL)
            ?: return errorResult("No URL provided")
        val importId = inputData.getString(KEY_IMPORT_ID) ?: id.toString()

        setForegroundProgress("Starting import...")

        val result = importRecipeUseCase.execute(
            url = url,
            onProgress = { progress ->
                val (workProgress, message) = when (progress) {
                    is ImportRecipeUseCase.ImportProgress.FetchingPage ->
                        RecipeImportWork.Progress.FetchingPage to "Fetching recipe page..."
                    is ImportRecipeUseCase.ImportProgress.PageMetadataAvailable ->
                        RecipeImportWork.Progress.MetadataAvailable(progress.title, progress.imageUrl) to
                            "Analyzing recipe..."
                    is ImportRecipeUseCase.ImportProgress.ParsingRecipe ->
                        RecipeImportWork.Progress.Parsing(recipeName = null) to "AI is analyzing the recipe..."
                    is ImportRecipeUseCase.ImportProgress.RecipeNameAvailable ->
                        RecipeImportWork.Progress.Parsing(progress.name) to "AI is analyzing the recipe..."
                    is ImportRecipeUseCase.ImportProgress.SavingRecipe ->
                        RecipeImportWork.Progress.Saving to "Saving recipe..."
                    is ImportRecipeUseCase.ImportProgress.Complete -> null to "Complete!"
                }
                workProgress?.let { setProgress(it.toData()) }
                setForegroundProgress(message)
            }
        )

        return when (result) {
            is ImportRecipeUseCase.ImportResult.Success -> {
                notificationHelper.showSuccessNotification(
                    key = importId,
                    recipeName = result.recipe.name,
                    recipeId = result.recipe.id
                )
                successResult(RecipeImportWork.Outcome.Success(importId, result.recipe.id, result.recipe.name))
            }
            is ImportRecipeUseCase.ImportResult.Error -> errorResult(
                RecipeImportWork.Outcome.Failed(result.message, importId),
                retryable = result.retryable
            )
            ImportRecipeUseCase.ImportResult.NoApiKey -> errorResult(
                RecipeImportWork.Outcome.NoApiKey("Add your Anthropic API key in Settings to import recipes.", importId),
                notify = false
            )
        }
    }
}

object RecipeImportWork : WorkContract<RecipeImportWork.Progress, RecipeImportWork.Outcome> {
    private const val KEY_PAGE_TITLE = "page_title"
    private const val KEY_IMAGE_URL = "image_url"

    private const val PROGRESS_FETCHING = "fetching"
    private const val PROGRESS_METADATA_AVAILABLE = "metadata_available"
    private const val PROGRESS_PARSING = "parsing"
    private const val PROGRESS_SAVING = "saving"

    sealed interface Progress {
        fun toData(): Data

        data object FetchingPage : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_FETCHING)
        }

        data class MetadataAvailable(val pageTitle: String?, val imageUrl: String?) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_METADATA_AVAILABLE,
                KEY_PAGE_TITLE to pageTitle,
                KEY_IMAGE_URL to imageUrl
            )
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
        /** [recipeId] is null only if the output is malformed. */
        data class Success(val importId: String?, val recipeId: String?, val recipeName: String?) :
            Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_IMPORT_ID to importId,
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                WorkResult.KEY_RECIPE_ID to recipeId,
                WorkResult.KEY_RECIPE_NAME to recipeName
            )
        }

        data class NoApiKey(override val message: String?, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.NO_API_KEY, message, WorkResult.KEY_IMPORT_ID to importId)
        }

        data class Failed(override val message: String?, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.ERROR, message, WorkResult.KEY_IMPORT_ID to importId)
        }
    }

    override fun decodeProgress(data: Data): Progress? = when (WorkResult.progress(data)) {
        PROGRESS_FETCHING -> Progress.FetchingPage
        PROGRESS_METADATA_AVAILABLE ->
            Progress.MetadataAvailable(data.getString(KEY_PAGE_TITLE), data.getString(KEY_IMAGE_URL))
        PROGRESS_PARSING -> Progress.Parsing(data.getString(WorkResult.KEY_RECIPE_NAME))
        PROGRESS_SAVING -> Progress.Saving
        else -> null
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome {
        val importId = data.getString(WorkResult.KEY_IMPORT_ID)
        return when {
            succeeded -> Outcome.Success(
                importId = importId,
                recipeId = data.getString(WorkResult.KEY_RECIPE_ID),
                recipeName = data.getString(WorkResult.KEY_RECIPE_NAME)
            )
            WorkResult.type(data) == WorkResult.NO_API_KEY -> Outcome.NoApiKey(WorkResult.message(data), importId)
            else -> Outcome.Failed(WorkResult.message(data), importId)
        }
    }
}
