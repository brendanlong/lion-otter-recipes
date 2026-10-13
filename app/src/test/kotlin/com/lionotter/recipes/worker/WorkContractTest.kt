package com.lionotter.recipes.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.ImageChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Every Progress/Outcome variant must survive encode → decode, and data written by earlier app
 * versions (pinned here as literal keys/values) must still decode.
 */
class WorkContractTest {

    private fun <P : Any, O : Any> assertProgressRoundTrip(contract: WorkContract<P, O>, progress: P, data: Data) {
        assertEquals(progress, contract.decodeProgress(data))
    }

    private fun <P : Any, O : Any> assertOutcomeRoundTrip(contract: WorkContract<P, O>, outcome: O, succeeded: Boolean, data: Data) {
        assertEquals(outcome, contract.decodeOutcome(succeeded, data))
    }

    @Test
    fun `recipe import progress round trips`() {
        listOf(
            RecipeImportWork.Progress.FetchingPage,
            RecipeImportWork.Progress.MetadataAvailable("Title", "https://img"),
            RecipeImportWork.Progress.MetadataAvailable(null, null),
            RecipeImportWork.Progress.Parsing("Pasta"),
            RecipeImportWork.Progress.Parsing(null),
            RecipeImportWork.Progress.Saving
        ).forEach { assertProgressRoundTrip(RecipeImportWork, it, it.toData()) }
    }

    @Test
    fun `recipe import outcomes round trip`() {
        listOf(
            RecipeImportWork.Outcome.Success("i1", "r1", "Pasta") to true,
            RecipeImportWork.Outcome.Failed(RecipeError.NoApiKey, "i1") to false,
            RecipeImportWork.Outcome.Failed(RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403), "i1") to false,
            RecipeImportWork.Outcome.Failed(RecipeError.NotARecipe("It's a blog post"), "i1") to false
        ).forEach { (outcome, succeeded) ->
            val data = (outcome as? SuccessOutcome)?.toData() ?: (outcome as FailureOutcome).toData()
            assertOutcomeRoundTrip(RecipeImportWork, outcome, succeeded, data)
        }
    }

    @Test
    fun `recipe import decodes data from earlier versions`() {
        assertEquals(
            RecipeImportWork.Outcome.Success("i1", "r1", "Pasta"),
            RecipeImportWork.decodeOutcome(
                succeeded = true,
                data = workDataOf("import_id" to "i1", "result_type" to "success", "recipe_id" to "r1", "recipe_name" to "Pasta")
            )
        )
        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.NoApiKey, "i1"),
            RecipeImportWork.decodeOutcome(
                succeeded = false,
                data = workDataOf("result_type" to "no_api_key", "error_message" to "msg", "import_id" to "i1")
            )
        )
        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.Legacy("The site blocked the request (403)."), "i1"),
            RecipeImportWork.decodeOutcome(
                succeeded = false,
                data = workDataOf(
                    "result_type" to "error",
                    "error_message" to "The site blocked the request (403).",
                    "import_id" to "i1"
                )
            )
        )
        assertEquals(
            RecipeImportWork.Progress.MetadataAvailable("T", "u"),
            RecipeImportWork.decodeProgress(
                workDataOf("import_id" to "i1", "progress" to "metadata_available", "page_title" to "T", "image_url" to "u")
            )
        )
    }

    @Test
    fun `missing or unknown progress decodes to null`() {
        assertNull(RecipeImportWork.decodeProgress(Data.EMPTY))
        assertNull(RecipeImportWork.decodeProgress(workDataOf("progress" to "something_new")))
    }

    @Test
    fun `failure without output decodes as an unexpected failure`() {
        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.Unexpected, null),
            RecipeImportWork.decodeOutcome(false, Data.EMPTY)
        )
        assertEquals(RecipeEditWork.Outcome.Failed(RecipeError.Unexpected, null), RecipeEditWork.decodeOutcome(false, Data.EMPTY))
        assertEquals(ZipExportWork.Outcome.Failed(RecipeError.Unexpected), ZipExportWork.decodeOutcome(false, Data.EMPTY))
    }

    @Test
    fun `generic failure decodes as each worker's failure`() {
        val data = GenericFailure(RecipeError.Unexpected, "i1").toData()
        assertEquals(RecipeImportWork.Outcome.Failed(RecipeError.Unexpected, "i1"), RecipeImportWork.decodeOutcome(false, data))
        assertEquals(PaprikaImportWork.Outcome.Failed(RecipeError.Unexpected, "i1"), PaprikaImportWork.decodeOutcome(false, data))
        assertEquals(ZipImportWork.Outcome.Failed(RecipeError.Unexpected, "i1"), ZipImportWork.decodeOutcome(false, data))
    }

    @Test
    fun `failures still write the result types earlier versions wrote`() {
        fun type(error: RecipeError) = GenericFailure(error, null).toData().getString("result_type")
        assertEquals("no_api_key", type(RecipeError.NoApiKey))
        assertEquals("no_source_url", type(RecipeError.NoSourceUrl))
        assertEquals("error", type(RecipeError.Ai(AiFailure.OVERLOADED)))
    }

    @Test
    fun `an unreadable encoded error falls back to the result type`() {
        val data = workDataOf("result_type" to "no_source_url", "error" to "{\"type\":\"from_the_future\"}")
        assertEquals(RecipeRegenerateWork.Outcome.Failed(RecipeError.NoSourceUrl, null), RecipeRegenerateWork.decodeOutcome(false, data))
    }

    @Test
    fun `paprika progress round trips`() {
        listOf(
            PaprikaImportWork.Progress.Parsing,
            PaprikaImportWork.Progress.SubmittingBatch(12),
            PaprikaImportWork.Progress.WaitingForBatch(total = 12, succeeded = 5),
            PaprikaImportWork.Progress.ImportingRecipe("Soup", current = 3, total = 12, importedSoFar = 2, failedSoFar = 1)
        ).forEach { assertProgressRoundTrip(PaprikaImportWork, it, it.toData()) }
    }

    @Test
    fun `paprika outcomes round trip`() {
        listOf(
            PaprikaImportWork.Outcome.Success("i1", importedCount = 4, failedCount = 1) to true,
            PaprikaImportWork.Outcome.Continued("i1") to true,
            PaprikaImportWork.Outcome.Failed(RecipeError.NoApiKey, "i1") to false,
            PaprikaImportWork.Outcome.Failed(RecipeError.LimitReached(1000, 1000), "i1") to false
        ).forEach { (outcome, succeeded) ->
            val data = (outcome as? SuccessOutcome)?.toData() ?: (outcome as FailureOutcome).toData()
            assertOutcomeRoundTrip(PaprikaImportWork, outcome, succeeded, data)
        }
    }

    @Test
    fun `paprika decodes data from earlier versions`() {
        assertEquals(
            PaprikaImportWork.Outcome.Continued("i1"),
            PaprikaImportWork.decodeOutcome(true, workDataOf("import_id" to "i1", "continued" to true))
        )
        assertEquals(
            PaprikaImportWork.Outcome.Success("i1", 4, 1),
            PaprikaImportWork.decodeOutcome(
                true,
                workDataOf("result_type" to "success", "import_id" to "i1", "imported_count" to 4, "failed_count" to 1)
            )
        )
        assertEquals(
            PaprikaImportWork.Progress.WaitingForBatch(total = 9, succeeded = 2),
            PaprikaImportWork.decodeProgress(
                workDataOf("progress" to "waiting_for_batch", "total" to 9, "current" to 2, "progress_imported_count" to 2)
            )
        )
    }

    @Test
    fun `zip import round trips`() {
        listOf(
            ZipImportWork.Progress.Starting,
            ZipImportWork.Progress.Reading,
            ZipImportWork.Progress.ImportingRecipe("Soup", 2, 0)
        ).forEach { assertProgressRoundTrip(ZipImportWork, it, it.toData()) }
        listOf(
            ZipImportWork.Outcome.Success("i1", 3, 1, 2, 4) to true,
            ZipImportWork.Outcome.Failed(RecipeError.Archive(ArchiveFailure.UNREADABLE), "i1") to false
        ).forEach { (outcome, succeeded) ->
            val data = (outcome as? SuccessOutcome)?.toData() ?: (outcome as FailureOutcome).toData()
            assertOutcomeRoundTrip(ZipImportWork, outcome, succeeded, data)
        }
        assertEquals(
            ZipImportWork.Outcome.Success("i1", 3, 1, 2, 4),
            ZipImportWork.decodeOutcome(
                true,
                workDataOf(
                    "result_type" to "success", "import_id" to "i1", "imported_count" to 3, "failed_count" to 1,
                    "skipped_count" to 2, "meal_plans_imported" to 4
                )
            )
        )
    }

    @Test
    fun `zip export round trips`() {
        listOf(
            ZipExportWork.Progress.Starting,
            ZipExportWork.Progress.Exporting("Soup", 1, 5)
        ).forEach { assertProgressRoundTrip(ZipExportWork, it, it.toData()) }
        assertOutcomeRoundTrip(ZipExportWork, ZipExportWork.Outcome.Success(5, 1), true, ZipExportWork.Outcome.Success(5, 1).toData())
        val failed = ZipExportWork.Outcome.Failed(RecipeError.Archive(ArchiveFailure.WRITE_FAILED))
        assertOutcomeRoundTrip(ZipExportWork, failed, false, failed.toData())
        assertEquals(
            ZipExportWork.Outcome.Success(5, 1),
            ZipExportWork.decodeOutcome(true, workDataOf("result_type" to "success", "exported_count" to 5, "failed_count" to 1))
        )
    }

    @Test
    fun `recipe edit round trips`() {
        listOf(
            RecipeEditWork.Progress.Parsing("Pasta"),
            RecipeEditWork.Progress.Parsing(null),
            RecipeEditWork.Progress.Saving
        ).forEach { assertProgressRoundTrip(RecipeEditWork, it, it.toData()) }
        listOf(
            RecipeEditWork.Outcome.Success("r1", "Pasta", newRecipeId = "copy") to true,
            RecipeEditWork.Outcome.Success("r1", "Pasta", newRecipeId = null) to true,
            RecipeEditWork.Outcome.Failed(RecipeError.NoApiKey, "r1") to false,
            RecipeEditWork.Outcome.Failed(RecipeError.Ai(AiFailure.HTTP_ERROR, 418), "r1") to false
        ).forEach { (outcome, succeeded) ->
            val data = (outcome as? SuccessOutcome)?.toData() ?: (outcome as FailureOutcome).toData()
            assertOutcomeRoundTrip(RecipeEditWork, outcome, succeeded, data)
        }
        assertEquals(
            RecipeEditWork.Outcome.Success("r1", "Pasta", "copy"),
            RecipeEditWork.decodeOutcome(
                true,
                workDataOf("recipe_id" to "r1", "result_type" to "success", "recipe_name" to "Pasta", "new_recipe_id" to "copy")
            )
        )
        assertEquals(
            RecipeEditWork.Outcome.Failed(RecipeError.NoApiKey, "r1"),
            RecipeEditWork.decodeOutcome(
                false,
                workDataOf("result_type" to "no_api_key", "error_message" to "API key not configured", "recipe_id" to "r1")
            )
        )
    }

    @Test
    fun `recipe regenerate round trips`() {
        listOf(
            RecipeRegenerateWork.Progress.FetchingPage,
            RecipeRegenerateWork.Progress.Parsing("Pasta"),
            RecipeRegenerateWork.Progress.Saving
        ).forEach { assertProgressRoundTrip(RecipeRegenerateWork, it, it.toData()) }
        listOf(
            RecipeRegenerateWork.Outcome.Success("r1", "Pasta") to true,
            RecipeRegenerateWork.Outcome.Failed(RecipeError.NoApiKey, "r1") to false,
            RecipeRegenerateWork.Outcome.Failed(RecipeError.NoSourceUrl, "r1") to false,
            RecipeRegenerateWork.Outcome.Failed(RecipeError.RecipeNotFound, "r1") to false
        ).forEach { (outcome, succeeded) ->
            val data = (outcome as? SuccessOutcome)?.toData() ?: (outcome as FailureOutcome).toData()
            assertOutcomeRoundTrip(RecipeRegenerateWork, outcome, succeeded, data)
        }
        assertEquals(
            RecipeRegenerateWork.Outcome.Failed(RecipeError.NoSourceUrl, "r1"),
            RecipeRegenerateWork.decodeOutcome(
                false,
                workDataOf("result_type" to "no_source_url", "error_message" to "msg", "recipe_id" to "r1")
            )
        )
    }

    @Test
    fun `WorkInfo states map to work statuses`() {
        val id = UUID.randomUUID()
        fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
            WorkInfo(id = id, state = state, tags = emptySet(), outputData = output, progress = progress)

        assertEquals(WorkStatus.Missing, null.toWorkStatus(RecipeEditWork))
        assertEquals(WorkStatus.Queued, info(WorkInfo.State.ENQUEUED).toWorkStatus(RecipeEditWork))
        assertEquals(WorkStatus.Queued, info(WorkInfo.State.BLOCKED).toWorkStatus(RecipeEditWork))
        assertEquals(WorkStatus.Running(null), info(WorkInfo.State.RUNNING).toWorkStatus(RecipeEditWork))
        assertEquals(
            WorkStatus.Running(RecipeEditWork.Progress.Saving),
            info(WorkInfo.State.RUNNING, progress = RecipeEditWork.Progress.Saving.toData()).toWorkStatus(RecipeEditWork)
        )
        assertEquals(
            WorkStatus.Finished(RecipeEditWork.Outcome.Success("r1", "Pasta", null)),
            info(WorkInfo.State.SUCCEEDED, output = RecipeEditWork.Outcome.Success("r1", "Pasta", null).toData())
                .toWorkStatus(RecipeEditWork)
        )
        assertEquals(
            WorkStatus.Finished(RecipeEditWork.Outcome.Failed(RecipeError.Unexpected, "r1")),
            info(WorkInfo.State.FAILED, output = RecipeEditWork.Outcome.Failed(RecipeError.Unexpected, "r1").toData())
                .toWorkStatus(RecipeEditWork)
        )
        assertEquals(WorkStatus.Cancelled, info(WorkInfo.State.CANCELLED).toWorkStatus(RecipeEditWork))
    }

    @Test
    fun `the copy image in edit input round trips`() {
        listOf(null, ImageChange.Remove, ImageChange.Replace("file:///images/a.jpg")).forEach { change ->
            val data = RecipeEditWorker.createInputData("r1", "/md", "model", true, saveAsCopy = true, copyImage = change)
            assertEquals(change, RecipeEditWorker.copyImage(data))
        }
    }

    @Test
    fun `edit input from older versions has no copy image`() {
        val data = workDataOf(
            "recipe_id" to "r1",
            "markdown_path" to "/md",
            "model" to "model",
            "extended_thinking" to true,
            "save_as_copy" to true,
            "copy_recipe_id" to "copy",
            "ai_instructions" to null
        )
        assertNull(RecipeEditWorker.copyImage(data))
        assertNull(RecipeEditWorker.tags(data))
    }

    @Test
    fun `the tags in edit input round trip, including none`() {
        listOf(null, emptyList(), listOf("Dessert", "quick")).forEach { tags ->
            val data = RecipeEditWorker.createInputData("r1", "/md", "model", true, tags = tags)
            assertEquals(tags, RecipeEditWorker.tags(data))
        }
    }
}
