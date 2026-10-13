package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
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

        private const val KEY_RECIPE_ID = WorkResult.KEY_RECIPE_ID
        /** Path to a file holding the markdown (it can exceed WorkManager's 10 KB Data limit). */
        private const val KEY_MARKDOWN_PATH = "markdown_path"
        private const val KEY_MODEL = "model"
        private const val KEY_EXTENDED_THINKING = "extended_thinking"
        private const val KEY_SAVE_AS_COPY = "save_as_copy"
        private const val KEY_AI_INSTRUCTIONS = "ai_instructions"
        private const val KEY_COPY_RECIPE_ID = "copy_recipe_id"

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
            ?: return errorResult(RecipeError.Unexpected, "no recipe ID provided")
        val markdownPath = inputData.getString(KEY_MARKDOWN_PATH)
            ?: return errorResult(RecipeError.Unexpected, "no markdown text provided")
        val markdownText = inputStore.readWorkText(markdownPath)
            ?: return errorResult(RecipeError.Interrupted, "the edited recipe text was lost")
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
                val (workProgress, message) = when (progress) {
                    is EditRecipeUseCase.EditProgress.ParsingRecipe ->
                        RecipeEditWork.Progress.Parsing(recipeName = null) to "AI is processing your changes..."
                    is EditRecipeUseCase.EditProgress.RecipeNameAvailable ->
                        RecipeEditWork.Progress.Parsing(progress.name) to "AI is processing your changes..."
                    is EditRecipeUseCase.EditProgress.SavingRecipe ->
                        RecipeEditWork.Progress.Saving to "Saving recipe..."
                }
                setProgress(workProgress.toData())
                setForegroundProgress(message)
            }
        )

        val workResult = when (result) {
            is Outcome.Success -> {
                val recipe = result.value
                notificationHelper.showSuccessNotification(
                    key = operationKey,
                    recipeName = recipe.name,
                    recipeId = recipe.id,
                    title = if (saveAsCopy) "Recipe Copy Saved" else "Recipe Updated"
                )
                successResult(
                    RecipeEditWork.Outcome.Success(
                        recipeId = recipeId,
                        recipeName = recipe.name,
                        newRecipeId = if (saveAsCopy) recipe.id else null
                    )
                )
            }
            is Outcome.Failure -> errorResult(
                RecipeEditWork.Outcome.Failed(result.error, recipeId),
                notify = result.error != RecipeError.NoApiKey
            )
        }
        val retrying = result is Outcome.Failure && willRetry(result.error.retryable)
        if (!retrying) {
            inputStore.deleteWorkText(markdownPath)
        }
        return workResult
    }
}

object RecipeEditWork : WorkContract<RecipeEditWork.Progress, RecipeEditWork.Outcome> {
    private const val KEY_NEW_RECIPE_ID = "new_recipe_id"

    private const val PROGRESS_PARSING = "parsing"
    private const val PROGRESS_SAVING = "saving"

    sealed interface Progress {
        fun toData(): Data

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
        /** [newRecipeId] is the created copy for "save as copy", otherwise null. */
        data class Success(val recipeId: String?, val recipeName: String?, val newRecipeId: String?) :
            Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_RECIPE_ID to recipeId,
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                WorkResult.KEY_RECIPE_NAME to recipeName,
                KEY_NEW_RECIPE_ID to newRecipeId
            )
        }

        data class Failed(override val error: RecipeError, val recipeId: String?) : Outcome, FailureOutcome {
            override fun toData() = WorkResult.failure(error, WorkResult.KEY_RECIPE_ID to recipeId)
        }
    }

    override fun decodeProgress(data: Data): Progress? = when (WorkResult.progress(data)) {
        PROGRESS_PARSING -> Progress.Parsing(data.getString(WorkResult.KEY_RECIPE_NAME))
        PROGRESS_SAVING -> Progress.Saving
        else -> null
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome {
        val recipeId = data.getString(WorkResult.KEY_RECIPE_ID)
        return when {
            succeeded -> Outcome.Success(
                recipeId = recipeId,
                recipeName = data.getString(WorkResult.KEY_RECIPE_NAME),
                newRecipeId = data.getString(KEY_NEW_RECIPE_ID)
            )
            else -> Outcome.Failed(WorkResult.error(data), recipeId)
        }
    }
}
