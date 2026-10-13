package com.lionotter.recipes.testutil

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Fixtures with every field (including nested ones) set to a non-default value, so a
 * round trip that drops a field fails. DomainFixturesTest checks they stay complete when
 * fields are added.
 */
object DomainFixtures {
    val fullRecipe = Recipe(
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
                        stepNumber = 2,
                        instruction = "Mix flour and butter.",
                        ingredients = listOf(
                            Ingredient(
                                name = "flour",
                                modifier = "sifted",
                                notes = "divided",
                                amount = Amount(value = 250.0, unit = "g"),
                                density = 0.53,
                                alternates = listOf(Ingredient(name = "cake flour")),
                                optional = true
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

    val fullMealPlanEntry = MealPlanEntry(
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
}
