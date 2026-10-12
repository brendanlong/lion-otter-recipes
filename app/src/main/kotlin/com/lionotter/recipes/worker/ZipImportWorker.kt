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

        const val KEY_IMPORT_ID = BaseRecipeWorker.KEY_IMPORT_ID
        const val KEY_RESULT_TYPE = BaseRecipeWorker.KEY_RESULT_TYPE
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_SKIPPED_COUNT = "skipped_count"
        const val KEY_MEAL_PLANS_IMPORTED = "meal_plans_imported"
        const val KEY_ERROR_MESSAGE = BaseRecipeWorker.KEY_ERROR_MESSAGE
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = BaseRecipeWorker.RESULT_ERROR

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_READING = "reading"
        const val PROGRESS_IMPORTING = "importing"

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
                    val progressMessage = when (progress) {
                        is ImportFromZipUseCase.ImportProgress.Starting -> {
                            setProgress(workDataOf(
                                KEY_PROGRESS to PROGRESS_STARTING,
                                KEY_IMPORT_ID to importId
                            ))
                            "Starting import..."
                        }
                        is ImportFromZipUseCase.ImportProgress.ReadingZip -> {
                            setProgress(workDataOf(
                                KEY_PROGRESS to PROGRESS_READING,
                                KEY_IMPORT_ID to importId
                            ))
                            "Reading file..."
                        }
                        is ImportFromZipUseCase.ImportProgress.ImportingRecipe -> {
                            setProgress(workDataOf(
                                KEY_PROGRESS to PROGRESS_IMPORTING,
                                KEY_IMPORT_ID to importId,
                                KEY_RECIPE_NAME to progress.recipeName,
                                KEY_CURRENT to progress.current,
                                KEY_TOTAL to progress.total
                            ))
                            if (progress.total > 0) {
                                "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
                            } else {
                                "Importing ${progress.current}: ${progress.recipeName}"
                            }
                        }
                        is ImportFromZipUseCase.ImportProgress.Complete -> "Complete!"
                    }
                    setForegroundProgress(progressMessage)
                }
            )
        }

        return when (result) {
            is ImportFromZipUseCase.ImportResult.Success -> {
                inputStore.deleteImport(importId)
                cancelProgressNotification()
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
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_IMPORT_ID to importId,
                        KEY_IMPORTED_COUNT to result.importedCount,
                        KEY_FAILED_COUNT to result.failedCount,
                        KEY_SKIPPED_COUNT to result.skippedCount,
                        KEY_MEAL_PLANS_IMPORTED to result.mealPlansImported
                    )
                )
            }
            is ImportFromZipUseCase.ImportResult.Error -> {
                Log.w(TAG, "ZIP import $importId failed: ${result.message}")
                errorResult(result.message)
            }
        }
    }
}
