package com.lionotter.recipes.integration

import com.lionotter.recipes.data.remote.dto.CorruptRecipeException
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.testutil.DomainFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that every field of [Recipe] and [MealPlanEntry] survives the
 * domain -> DTO -> Firestore -> DTO -> domain round trip.
 */
class RecipeDtoRoundTripIntegrationTest : FirestoreIntegrationTest() {

    private val fullRecipe = DomainFixtures.fullRecipe

    @Test
    fun `recipe dto mapping preserves every field`() {
        assertEquals(fullRecipe, fullRecipe.toDto().toDomain())
    }

    @Test
    fun `recipe survives Firestore write and read with every field`() {
        recipeRepository.saveRecipe(fullRecipe)
        pumpLooper()

        val loaded = runSuspending { recipeRepository.getRecipeByIdOnce(fullRecipe.id) }
        assertEquals(fullRecipe, loaded)
    }

    @Test
    fun `setUserNotes is readable and saveRecipe preserves notes`() {
        recipeRepository.saveRecipe(fullRecipe.copy(userNotes = null))
        pumpLooper()
        recipeRepository.setUserNotes(fullRecipe.id, "Added later")
        pumpLooper()

        val withNotes = runSuspending { recipeRepository.getRecipeByIdOnce(fullRecipe.id) }!!
        assertEquals("Added later", withNotes.userNotes)

        recipeRepository.saveRecipe(withNotes.copy(name = "Renamed"))
        pumpLooper()

        val resaved = runSuspending { recipeRepository.getRecipeByIdOnce(fullRecipe.id) }!!
        assertEquals("Renamed", resaved.name)
        assertEquals("Added later", resaved.userNotes)
    }

    @Test
    fun `recipe with unreadable instructions is left out rather than loaded without them`() {
        recipeRepository.saveRecipe(fullRecipe)
        firestoreService.recipesCollection("ignored").document("corrupt")
            .set(fullRecipe.toDto().copy(id = "corrupt", instructionSectionsJson = "[{not json"))
        pumpLooper()
        val stored = runSuspending { firestoreService.recipesCollection("ignored").get().await().size() }
        assertEquals(2, stored)

        val listed = runSuspending { recipeRepository.getAllRecipes().first { it.isNotEmpty() } }
        assertEquals(listOf(fullRecipe.id), listed.map { it.id })

        val error = runCatching { runSuspending { recipeRepository.getRecipeByIdOnce("corrupt") } }.exceptionOrNull()
        assertTrue("Expected CorruptRecipeException, got $error", error is CorruptRecipeException)
    }

    @Test
    fun `raw Firestore document fields map to DTO`() {
        recipeRepository.saveRecipe(fullRecipe)
        pumpLooper()

        val dto = runSuspending {
            firestoreService.recipesCollection("ignored").document(fullRecipe.id).get().await()
                .toObject(RecipeDto::class.java)
        }!!
        assertEquals(true, dto.isFavorite)
        assertEquals("Use less sugar next time", dto.userNotes)
    }

    @Test
    fun `meal plan survives Firestore write and read with every field`() {
        val entry = DomainFixtures.fullMealPlanEntry
        assertEquals(entry, entry.toDto().toDomain())

        mealPlanRepository.saveMealPlan(entry)
        pumpLooper()

        val loaded = runSuspending {
            firestoreService.mealPlansCollection("ignored").document(entry.id).get().await()
                .toObject(MealPlanDto::class.java)?.toDomain()
        }
        assertEquals(entry, loaded)
    }
}
