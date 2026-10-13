package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.FakeTagOrganizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TagCleanupUseCaseTest {
    private val repository = FakeRecipeRepository(
        recipe("cake", "Dessert", "Baking"),
        recipe("pie", "desserts", "recipe"),
        recipe("soup", "Dinner")
    )
    private val organizer = FakeTagOrganizer()
    private val settings = FakeAiSettings()
    private val useCase = TagCleanupUseCase(repository, organizer, settings)

    private fun recipe(id: String, vararg tags: String) = Recipe(
        id = id,
        name = id,
        tags = tags.toList(),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun `sends every tag with its count to the edit model and plans the answer`() = runTest {
        settings.editModelFlow.value = AiModels.MODEL_SONNET
        settings.thinkingFlow.value = true
        organizer.result = Outcome.Success(
            listOf(TagMapping("desserts", "Dessert"), TagMapping("recipe", null), TagMapping("Unknown", "x"))
        )

        val result = useCase.suggestChanges()

        val request = organizer.requests.single()
        assertEquals(
            setOf(TagUsage("Dessert", 1), TagUsage("Baking", 1), TagUsage("desserts", 1), TagUsage("recipe", 1), TagUsage("Dinner", 1)),
            request.tags.toSet()
        )
        assertEquals("sk-ant-test", request.apiKey)
        assertEquals(AiModels.MODEL_SONNET, request.model)
        assertTrue(request.thinkingEnabled)
        assertEquals(
            Outcome.Success(
                listOf(
                    TagChange("desserts", "Dessert", 1, TagChange.Kind.MERGE),
                    TagChange("recipe", null, 1, TagChange.Kind.DELETE)
                )
            ),
            result
        )
    }

    @Test
    fun `uses Haiku by default`() = runTest {
        useCase.suggestChanges()
        assertEquals(AiModels.DEFAULT_EDIT_MODEL, organizer.requests.single().model)
        assertEquals(AiModels.MODEL_HAIKU, AiModels.DEFAULT_EDIT_MODEL)
    }

    @Test
    fun `fails without an API key, before reading or calling anything`() = runTest {
        settings.apiKeyFlow.value = " "
        assertEquals(Outcome.Failure(RecipeError.NoApiKey), useCase.suggestChanges())
        assertTrue(organizer.requests.isEmpty())
    }

    @Test
    fun `no tags means no suggestions and no AI call`() = runTest {
        val empty = TagCleanupUseCase(FakeRecipeRepository(recipe("plain")), organizer, settings)
        assertEquals(Outcome.Success(emptyList<TagChange>()), empty.suggestChanges())
        assertTrue(organizer.requests.isEmpty())
    }

    @Test
    fun `passes AI and read failures on`() = runTest {
        organizer.result = Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429))
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429)), useCase.suggestChanges())

        repository.tagsError = IOException("offline")
        assertEquals(Outcome.Failure(RecipeError.Unexpected), useCase.suggestChanges())
    }

    @Test
    fun `applies the changes to the recipes that carry the tags`() = runTest {
        val changes = listOf(
            TagChange("desserts", "Dessert", 1, TagChange.Kind.MERGE),
            TagChange("recipe", null, 1, TagChange.Kind.DELETE)
        )

        assertEquals(Outcome.Success(1), useCase.applyChanges(changes))

        assertEquals(listOf("pie" to listOf("Dessert")), repository.tagUpdates)
        assertEquals(listOf("Dessert", "Baking"), repository.recipes.value.getValue("cake").tags)
        assertEquals(listOf("Dinner"), repository.recipes.value.getValue("soup").tags)
    }

    @Test
    fun `fails without writing when the recipes can't be read`() = runTest {
        repository.tagsError = IOException("offline")
        val result = useCase.applyChanges(listOf(TagChange("recipe", null, 1, TagChange.Kind.DELETE)))
        assertEquals(Outcome.Failure(RecipeError.Unexpected), result)
        assertTrue(repository.tagUpdates.isEmpty())
    }

    @Test
    fun `applying finishes even if the caller is cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.beforeTagsRead = { gate.await() }
        val job = launch { useCase.applyChanges(listOf(TagChange("Dinner", "Supper", 1, TagChange.Kind.RENAME))) }
        runCurrent()

        job.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(listOf("soup" to listOf("Supper")), repository.tagUpdates)
    }
}
