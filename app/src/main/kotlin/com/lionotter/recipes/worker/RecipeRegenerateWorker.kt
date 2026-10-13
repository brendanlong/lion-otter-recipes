package com.lionotter.recipes.worker

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.WorkInfo
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.usecase.RegenerateRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecipeRegenerateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val regenerateRecipeUseCase: RegenerateRecipeUseCase,
    private val inputStore: ImportInputStore,
    notificationHelper: RecipeNotificationHelper,
    runningWork: RunningAccountWork
) : BaseRecipeWorker(context, workerParams, notificationHelper, runningWork) {

    override val notificationTitle = "Regenerating Recipe"
    override val errorTitle = "Regeneration Failed"
    override val operationKey: String get() = inputData.getString(KEY_RECIPE_ID) ?: id.toString()

    companion object {
        const val TAG_RECIPE_REGENERATE = "recipe_regenerate"

        private const val KEY_RECIPE_ID = WorkResult.KEY_RECIPE_ID
        private const val KEY_MODEL = "model"
        private const val KEY_EXTENDED_THINKING = "extended_thinking"
        private const val KEY_PAGE_HTML_PATH = "page_html_path"

        /**
         * @param pageHtmlPath the source page captured in the in-app browser, written with
         *   [ImportInputStore.writeWorkText]; the worker deletes it once no run needs it. Without
         *   one the worker downloads the recipe's source URL.
         */
        fun createInputData(
            recipeId: String,
            model: String?,
            thinkingEnabled: Boolean?,
            pageHtmlPath: String? = null
        ): Data {
            return workDataOf(
                KEY_RECIPE_ID to recipeId,
                KEY_MODEL to model,
                KEY_EXTENDED_THINKING to thinkingEnabled,
                KEY_PAGE_HTML_PATH to pageHtmlPath
            )
        }
    }

    override suspend fun doRecipeWork(): Result {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
            ?: return errorResult(RecipeError.Unexpected, "no recipe ID provided")
        val model = inputData.getString(KEY_MODEL)
        val thinkingEnabled = if (inputData.keyValueMap.containsKey(KEY_EXTENDED_THINKING)) {
            inputData.getBoolean(KEY_EXTENDED_THINKING, AiModels.DEFAULT_THINKING_ENABLED)
        } else {
            null
        }

        setForegroundProgress("Starting regeneration...")

        val pageHtmlPath = inputData.getString(KEY_PAGE_HTML_PATH)
        var result: Result? = null
        try {
            result = regenerate(recipeId, model, thinkingEnabled, pageHtmlPath)
            return result
        } finally {
            val keepPage = when {
                result != null -> result == Result.retry()
                // Stopped by the system, the work runs again; cancelled by the user, it doesn't.
                // Below API 31 the reason is unknown and the page is kept (stale work inputs are
                // cleaned up later).
                isStopped -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP
                // An unexpected exception fails the work for good
                else -> false
            }
            if (pageHtmlPath != null && !keepPage) inputStore.deleteWorkText(pageHtmlPath)
        }
    }

    private suspend fun regenerate(
        recipeId: String,
        model: String?,
        thinkingEnabled: Boolean?,
        pageHtmlPath: String?
    ): Result {
        val pageHtml = pageHtmlPath?.let { path ->
            inputStore.readWorkText(path)
                ?: return errorResult(RecipeError.Interrupted, "the captured page was lost")
        }

        val result = regenerateRecipeUseCase.execute(
            recipeId = recipeId,
            pageHtml = pageHtml,
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
                }
                setProgress(workProgress.toData())
                setForegroundProgress(message)
            }
        )

        return when (result) {
            is Outcome.Success -> {
                val recipe = result.value
                notificationHelper.showSuccessNotification(
                    key = operationKey,
                    recipeName = recipe.name,
                    recipeId = recipe.id,
                    title = "Recipe Regenerated"
                )
                successResult(RecipeRegenerateWork.Outcome.Success(recipeId, recipe.name))
            }
            is Outcome.Failure -> errorResult(
                RecipeRegenerateWork.Outcome.Failed(result.error, recipeId),
                notify = result.error != RecipeError.NoApiKey
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

        data class Failed(override val error: RecipeError, val recipeId: String?) : Outcome, FailureOutcome {
            override fun toData() = WorkResult.failure(error, WorkResult.KEY_RECIPE_ID to recipeId)
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
        return if (succeeded) {
            Outcome.Success(recipeId, data.getString(WorkResult.KEY_RECIPE_NAME))
        } else {
            Outcome.Failed(WorkResult.error(data), recipeId)
        }
    }
}
