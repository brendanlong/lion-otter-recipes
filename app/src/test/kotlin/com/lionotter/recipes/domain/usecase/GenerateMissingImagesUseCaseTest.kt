package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class GenerateMissingImagesUseCaseTest {
    private fun recipe(id: String, imageUrl: String? = null) = Recipe(
        id = id,
        name = id,
        imageUrl = imageUrl,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    private val repository = FakeRecipeRepository(recipe("a"), recipe("b", "gs://bucket/b.jpg"), recipe("c"))
    private val scheduler = FakeRecipeImageScheduler()
    private val useCase = GenerateMissingImagesUseCase(repository, scheduler)

    @Test
    fun `finds the recipes without images and schedules them`() = runTest {
        val missing = useCase.recipesWithoutImages()
        assertEquals(listOf("a", "c"), missing)

        useCase.execute(missing)

        assertEquals(
            listOf(FakeRecipeImageScheduler.Scheduled("a"), FakeRecipeImageScheduler.Scheduled("c")),
            scheduler.scheduled
        )
    }
}
