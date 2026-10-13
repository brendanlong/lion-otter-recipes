package com.lionotter.recipes.integration

import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** The repository queries the image layer uses, against the real Firestore SDK (offline). */
class ImageReferenceIntegrationTest : FirestoreIntegrationTest() {

    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun recipe(id: String, imageUrl: String?) =
        Recipe(id = id, name = id, imageUrl = imageUrl, createdAt = epoch, updatedAt = epoch)

    private fun mealPlan(id: String, recipeId: String, imageUrl: String?) = MealPlanEntry(
        id = id, recipeId = recipeId, recipeName = recipeId, recipeImageUrl = imageUrl,
        date = LocalDate(2024, 1, 15), mealType = MealType.DINNER, createdAt = epoch, updatedAt = epoch
    )

    @Test
    fun `an image is referenced by recipes other than the excluded one`() {
        recipeRepository.saveRecipe(recipe("original", "gs://bucket/shared.jpg"))
        recipeRepository.saveRecipe(recipe("copy", "gs://bucket/shared.jpg"))
        recipeRepository.saveRecipe(recipe("other", "gs://bucket/other.jpg"))
        pumpLooper()

        assertTrue(runSuspending { recipeRepository.isImageReferenced("gs://bucket/shared.jpg", "original") })
        assertFalse(runSuspending { recipeRepository.isImageReferenced("gs://bucket/other.jpg", "other") })
        assertTrue(runSuspending { recipeRepository.isImageReferenced("gs://bucket/other.jpg") })
        assertFalse(runSuspending { recipeRepository.isImageReferenced("gs://bucket/none.jpg") })

        recipeRepository.deleteRecipe("copy")
        pumpLooper()
        assertFalse(runSuspending { recipeRepository.isImageReferenced("gs://bucket/shared.jpg", "original") })
    }

    @Test
    fun `image URLs by recipe, from the server or only the device`() {
        recipeRepository.saveRecipe(recipe("r1", "file:///local.jpg"))
        recipeRepository.saveRecipe(recipe("r2", "gs://bucket/b.jpg"))
        recipeRepository.saveRecipe(recipe("r3", null))
        pumpLooper()

        assertEquals("file:///local.jpg", runSuspending { recipeRepository.getImageUrl("r1") })
        assertEquals(null, runSuspending { recipeRepository.getImageUrl("r3") })
        assertEquals(null, runSuspending { recipeRepository.getImageUrl("missing") })

        val expected = mapOf("r1" to "file:///local.jpg", "r2" to "gs://bucket/b.jpg")
        assertEquals(expected, runSuspending { recipeRepository.getImageUrlsByRecipeId() })
        assertEquals(expected, runSuspending { recipeRepository.getImageUrlsByRecipeId(localOnly = true) })
    }

    @Test
    fun `meal plans follow a moved recipe image`() {
        mealPlanRepository.saveMealPlan(mealPlan("m1", "r1", "file:///local.jpg"))
        mealPlanRepository.saveMealPlan(mealPlan("m2", "r1", "file:///local.jpg"))
        mealPlanRepository.saveMealPlan(mealPlan("m3", "r2", "file:///other.jpg"))
        pumpLooper()

        runSuspending { mealPlanRepository.replaceRecipeImageUrl("file:///local.jpg", "gs://bucket/local.jpg") }
        pumpLooper()

        val images = runSuspending {
            mealPlanRepository.getMealPlansForDateRange(LocalDate(2024, 1, 1), LocalDate(2024, 1, 31)).first()
        }.associate { it.id to it.recipeImageUrl }
        assertEquals(
            mapOf("m1" to "gs://bucket/local.jpg", "m2" to "gs://bucket/local.jpg", "m3" to "file:///other.jpg"),
            images
        )
    }
}
