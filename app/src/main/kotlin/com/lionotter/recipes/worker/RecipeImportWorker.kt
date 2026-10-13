package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importRecipeUseCase: ImportRecipeUseCase,
    private val inputStore: ImportInputStore,
    private val attachmentLoader: RecipeAttachmentLoader,
    notificationHelper: RecipeNotificationHelper,
    runningWork: RunningAccountWork
) : BaseRecipeWorker(context, workerParams, notificationHelper, runningWork) {

    override val notificationTitle = "Importing Recipe"
    override val errorTitle = "Import Failed"

    companion object {
        const val TAG_RECIPE_IMPORT = "recipe_import"

        private const val KEY_URL = "url"

        /**
         * A page captured in the in-app browser is not part of the input: it is stored in
         * [ImportInputStore] under [importId] (see [ImportInputStore.adoptPageHtml]). Without
         * one the worker downloads [url]. A recipe file import has no [url]; its file is stored
         * under [importId] too.
         */
        fun createInputData(url: String?, importId: String): Data = workDataOf(
            KEY_URL to url,
            KEY_IMPORT_ID to importId
        )
    }

    override suspend fun doRecipeWork(): Result {
        val importId = inputData.getString(KEY_IMPORT_ID) ?: id.toString()

        setForegroundProgress("Starting import...")

        val (recipeFile, pageHtml) = inputStore.withImportLock(importId) {
            val file = inputStore.readRecipeFile(importId)
            file to (if (file == null) inputStore.readPageHtml(importId) else null)
        }
        val onProgress: suspend (ImportRecipeUseCase.ImportProgress) -> Unit = { progress ->
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
            }
            setProgress(workProgress.toData())
            setForegroundProgress(message)
        }

        val result = if (recipeFile != null && !recipeFile.format.isText) {
            if (recipeFile.sources.isEmpty()) {
                return errorResult(RecipeError.CannotRetry, "the recipe files to import are gone")
            }
            val attachments = try {
                attachmentLoader.load(recipeFile.format, recipeFile.sources)
            } catch (e: RecipeErrorException) {
                return errorResult(RecipeImportWork.Outcome.Failed(e.recipeError, importId))
            }
            importRecipeUseCase.importAttachments(attachments, onProgress)
        } else if (recipeFile != null) {
            val content = recipeFile.content
                ?: return errorResult(RecipeError.CannotRetry, "the recipe file to import is gone")
            importRecipeUseCase.importFile(content, recipeFile.format, onProgress)
        } else {
            val url = inputData.getString(KEY_URL)?.takeIf { it.isNotBlank() }
                ?: return errorResult(RecipeError.CannotRetry, "no URL and no recipe file to import")
            importRecipeUseCase.execute(url = url, pageHtml = pageHtml, onProgress = onProgress)
        }

        return when (result) {
            is Outcome.Success -> {
                val recipe = result.value
                if (recipeFile != null || pageHtml != null) inputStore.deleteImport(importId)
                notificationHelper.showSuccessNotification(
                    key = importId,
                    recipeName = recipe.name,
                    recipeId = recipe.id
                )
                successResult(RecipeImportWork.Outcome.Success(importId, recipe.id, recipe.name))
            }
            is Outcome.Failure -> errorResult(
                RecipeImportWork.Outcome.Failed(result.error, importId),
                notify = result.error != RecipeError.NoApiKey
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

        data class Failed(override val error: RecipeError, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() = WorkResult.failure(error, WorkResult.KEY_IMPORT_ID to importId)
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
            else -> Outcome.Failed(WorkResult.error(data), importId)
        }
    }
}
