package com.lionotter.recipes.worker

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.ImageChange
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.toRecipeError
import com.lionotter.recipes.domain.usecase.EditRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@HiltWorker
class RecipeEditWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val editRecipeUseCase: EditRecipeUseCase,
    private val inputStore: ImportInputStore,
    private val imageStore: ImageStore,
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

        /** Present (true) when the copy gets a different image than the original. */
        private const val KEY_COPY_IMAGE_CHANGED = "copy_image_changed"

        /** The copy's new local image, owned by this work; absent with a change means no image. */
        private const val KEY_COPY_IMAGE_URI = "copy_image_uri"

        fun createInputData(
            recipeId: String,
            markdownPath: String,
            model: String?,
            thinkingEnabled: Boolean?,
            saveAsCopy: Boolean = false,
            aiInstructions: String? = null,
            copyRecipeId: String? = null,
            copyImage: ImageChange? = null
        ): Data {
            return workDataOf(
                KEY_RECIPE_ID to recipeId,
                KEY_MARKDOWN_PATH to markdownPath,
                KEY_MODEL to model,
                KEY_EXTENDED_THINKING to thinkingEnabled,
                KEY_SAVE_AS_COPY to saveAsCopy,
                KEY_COPY_RECIPE_ID to copyRecipeId,
                // Instructions are user-typed; keep them well under the Data size limit.
                KEY_AI_INSTRUCTIONS to aiInstructions?.take(4000),
                KEY_COPY_IMAGE_CHANGED to (copyImage != null),
                KEY_COPY_IMAGE_URI to (copyImage as? ImageChange.Replace)?.localUri
            )
        }

        /** The image change for the copy; null for work enqueued without one (or by older versions). */
        fun copyImage(inputData: Data): ImageChange? = when {
            !inputData.getBoolean(KEY_COPY_IMAGE_CHANGED, false) -> null
            else -> inputData.getString(KEY_COPY_IMAGE_URI)?.let(ImageChange::Replace) ?: ImageChange.Remove
        }
    }

    override suspend fun doRecipeWork(): Result {
        val copyImage = if (inputData.getBoolean(KEY_SAVE_AS_COPY, false)) copyImage(inputData) else null
        // The copy's image file belongs to this work until it is stored for the copy; release it
        // once no run will need it
        var releaseCopyImage = false
        try {
            val result = edit(copyImage)
            releaseCopyImage = !copyImageStored && result != Result.retry()
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            releaseCopyImage = !willRetry(e.toRecipeError().retryable)
            throw e
        } finally {
            // Stopped by the system, the work runs again; cancelled by the user, it doesn't. Below
            // API 31 the reason is unknown and the file is kept.
            val cancelledByUser = isStopped && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
            if ((releaseCopyImage || cancelledByUser) && copyImage is ImageChange.Replace) {
                withContext(NonCancellable) { imageStore.deleteLocalImage(copyImage.localUri) }
            }
        }
    }

    /** Set once the copy (with its image) has been saved. */
    private var copyImageStored = false

    private suspend fun edit(copyImage: ImageChange?): Result {
        val recipeId = inputData.getString(KEY_RECIPE_ID)
            ?: return errorResult(RecipeError.Unexpected, "no recipe ID provided")
        val markdownPath = inputData.getString(KEY_MARKDOWN_PATH)
            ?: return errorResult(RecipeError.Unexpected, "no markdown text provided")
        val markdownText = inputStore.readWorkText(markdownPath)
            ?: return errorResult(RecipeError.Interrupted, "the edited recipe text was lost")
        val model = inputData.getString(KEY_MODEL)
        val thinkingEnabled = if (inputData.keyValueMap.containsKey(KEY_EXTENDED_THINKING)) {
            inputData.getBoolean(KEY_EXTENDED_THINKING, AiModels.DEFAULT_THINKING_ENABLED)
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
            copyImage = copyImage,
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
                copyImageStored = true
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
