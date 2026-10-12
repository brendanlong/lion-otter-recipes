package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportFromZipUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ZipImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importFromZipUseCase: ImportFromZipUseCase,
    private val inputStore: ImportInputStore,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing recipes"
    override val errorTitle = "Import Failed"

    companion object {
        private const val TAG = "ZipImportWorker"
        const val TAG_ZIP_IMPORT = "zip_import"

        /** The file and selection live in [ImportInputStore] under [importId]. */
        fun createInputData(importId: String): Data = workDataOf(KEY_IMPORT_ID to importId)
    }

    override suspend fun doRecipeWork(): Result {
        val importId = inputData.getString(KEY_IMPORT_ID)
            ?: return errorResult("No import ID provided")
        val sourceFile = inputStore.sourceFile(importId)
        if (!sourceFile.exists()) {
            return errorResult("The file to import is no longer available. Please choose it again.")
        }
        val selectedRecipeIds = inputStore.readSelection(importId)
        setForegroundProgress("Starting import...")

        val result = sourceFile.inputStream().buffered().use { stream ->
            importFromZipUseCase.importFromZip(
                inputStream = stream,
                selectedRecipeIds = selectedRecipeIds,
                onProgress = { progress ->
                    val (workProgress, message) = when (progress) {
                        is ImportFromZipUseCase.ImportProgress.Starting ->
                            ZipImportWork.Progress.Starting to "Starting import..."
                        is ImportFromZipUseCase.ImportProgress.ReadingZip ->
                            ZipImportWork.Progress.Reading to "Reading file..."
                        is ImportFromZipUseCase.ImportProgress.ImportingRecipe ->
                            ZipImportWork.Progress.ImportingRecipe(progress.recipeName, progress.current, progress.total) to
                                if (progress.total > 0) {
                                    "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
                                } else {
                                    "Importing ${progress.current}: ${progress.recipeName}"
                                }
                        is ImportFromZipUseCase.ImportProgress.Complete -> null to "Complete!"
                    }
                    workProgress?.let { setProgress(it.toData()) }
                    setForegroundProgress(message)
                }
            )
        }

        return when (result) {
            is ImportFromZipUseCase.ImportResult.Success -> {
                inputStore.deleteImport(importId)
                notificationHelper.showSummaryNotification(
                    key = importId,
                    title = "Import Complete",
                    message = notificationHelper.zipImportMessage(
                        importedCount = result.importedCount,
                        failedCount = result.failedCount,
                        skippedCount = result.skippedCount,
                        mealPlansImported = result.mealPlansImported
                    )
                )
                successResult(
                    ZipImportWork.Outcome.Success(
                        importId = importId,
                        importedCount = result.importedCount,
                        failedCount = result.failedCount,
                        skippedCount = result.skippedCount,
                        mealPlansImported = result.mealPlansImported
                    )
                )
            }
            is ImportFromZipUseCase.ImportResult.Error -> {
                Log.w(TAG, "ZIP import $importId failed: ${result.message}")
                errorResult(ZipImportWork.Outcome.Failed(result.message, importId))
            }
        }
    }
}

object ZipImportWork : WorkContract<ZipImportWork.Progress, ZipImportWork.Outcome> {
    private const val KEY_IMPORTED_COUNT = "imported_count"
    private const val KEY_FAILED_COUNT = "failed_count"
    private const val KEY_SKIPPED_COUNT = "skipped_count"
    private const val KEY_MEAL_PLANS_IMPORTED = "meal_plans_imported"
    private const val KEY_CURRENT = "current"
    private const val KEY_TOTAL = "total"

    private const val PROGRESS_STARTING = "starting"
    private const val PROGRESS_READING = "reading"
    private const val PROGRESS_IMPORTING = "importing"

    sealed interface Progress {
        fun toData(): Data

        data object Starting : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_STARTING)
        }

        data object Reading : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_READING)
        }

        /** [total] is 0 when the number of recipes isn't known yet. */
        data class ImportingRecipe(val recipeName: String?, val current: Int, val total: Int) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_IMPORTING,
                WorkResult.KEY_RECIPE_NAME to recipeName,
                KEY_CURRENT to current,
                KEY_TOTAL to total
            )
        }
    }

    sealed interface Outcome {
        data class Success(
            val importId: String?,
            val importedCount: Int,
            val failedCount: Int,
            val skippedCount: Int,
            val mealPlansImported: Int
        ) : Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                WorkResult.KEY_IMPORT_ID to importId,
                KEY_IMPORTED_COUNT to importedCount,
                KEY_FAILED_COUNT to failedCount,
                KEY_SKIPPED_COUNT to skippedCount,
                KEY_MEAL_PLANS_IMPORTED to mealPlansImported
            )
        }

        data class Failed(override val message: String?, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.ERROR, message, WorkResult.KEY_IMPORT_ID to importId)
        }
    }

    override fun decodeProgress(data: Data): Progress? = when (WorkResult.progress(data)) {
        PROGRESS_STARTING -> Progress.Starting
        PROGRESS_READING -> Progress.Reading
        PROGRESS_IMPORTING -> Progress.ImportingRecipe(
            recipeName = data.getString(WorkResult.KEY_RECIPE_NAME),
            current = data.getInt(KEY_CURRENT, 0),
            total = data.getInt(KEY_TOTAL, 0)
        )
        else -> null
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome {
        val importId = data.getString(WorkResult.KEY_IMPORT_ID)
        return if (succeeded) {
            Outcome.Success(
                importId = importId,
                importedCount = data.getInt(KEY_IMPORTED_COUNT, 0),
                failedCount = data.getInt(KEY_FAILED_COUNT, 0),
                skippedCount = data.getInt(KEY_SKIPPED_COUNT, 0),
                mealPlansImported = data.getInt(KEY_MEAL_PLANS_IMPORTED, 0)
            )
        } else {
            Outcome.Failed(WorkResult.message(data), importId)
        }
    }
}
