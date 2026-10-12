package com.lionotter.recipes.integration

import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * Verifies that every field of [Recipe] and [MealPlanEntry] survives the
 * domain -> DTO -> Firestore -> DTO -> domain round trip.
 */
class RecipeDtoRoundTripIntegrationTest : FirestoreIntegrationTest() {

    private val fullRecipe = Recipe(
        id = "round-trip",
        name = "Round Trip Pie",
        sourceUrl = "https://example.com/pie",
        story = "A story",
        servings = 8,
        prepTime = "20 min",
        cookTime = "45 min",
        totalTime = "1 hr 5 min",
        instructionSections = listOf(
            InstructionSection(
                name = "Crust",
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Mix flour and butter.",
                        ingredients = listOf(
                            Ingredient(
                                name = "flour",
                                notes = "sifted",
                                amount = Amount(value = 250.0, unit = "g"),
                                density = 0.53,
                                alternates = listOf(Ingredient(name = "cake flour")),
                                optional = false
                            )
                        ),
                        yields = 2,
                        optional = true
                    )
                )
            )
        ),
        equipment = listOf("pie dish"),
        tags = listOf("dessert", "baking"),
        imageUrl = "gs://bucket/users/u/images/pie.jpg",
        sourceImageUrl = "https://example.com/pie.jpg",
        createdAt = Instant.fromEpochSeconds(1_700_000_000, 123_000_000),
        updatedAt = Instant.fromEpochSeconds(1_700_000_500, 456_000_000),
        isFavorite = true,
        userNotes = "Use less sugar next time"
    )

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
        val entry = MealPlanEntry(
            id = "mp-round-trip",
            recipeId = "round-trip",
            recipeName = "Round Trip Pie",
            recipeImageUrl = "file:///data/pie.jpg",
            date = LocalDate(2026, 3, 14),
            mealType = MealType.LUNCH,
            servings = 2.5,
            createdAt = Instant.fromEpochSeconds(1_700_000_000, 1_000),
            updatedAt = Instant.fromEpochSeconds(1_700_000_100, 2_000)
        )
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
