package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.time.Instant

class AggregateGroceryListUseCaseTest {

    private val useCase = AggregateGroceryListUseCase()
    private val now = Instant.fromEpochMilliseconds(0)

    private fun recipe(id: String, name: String, vararg sections: Pair<String?, List<Ingredient>>) = Recipe(
        id = id,
        name = name,
        instructionSections = sections.map { (sectionName, ingredients) ->
            InstructionSection(
                name = sectionName,
                steps = listOf(InstructionStep(stepNumber = 1, instruction = "Do it", ingredients = ingredients))
            )
        },
        createdAt = now,
        updatedAt = now
    )

    private fun entry(id: String, recipe: Recipe, servings: Double = 1.0) = MealPlanEntry(
        id = id,
        recipeId = recipe.id,
        recipeName = recipe.name,
        recipeImageUrl = null,
        date = LocalDate(2026, 1, 1),
        mealType = MealType.DINNER,
        servings = servings,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun `same ingredient in two sections of one recipe sums amounts`() {
        val cake = recipe(
            "cake", "Cake",
            "Cake" to listOf(Ingredient(name = "butter", amount = Amount(1.0, "cup"))),
            "Frosting" to listOf(Ingredient(name = "butter", amount = Amount(0.5, "cup")))
        )

        val items = useCase.execute(listOf(entry("e1", cake) to cake))

        val source = items.single().sources.single()
        assertEquals(
            "1 1/2 cups butter",
            source.ingredient.format(scale = source.scale, volumeSystem = UnitSystem.CUSTOMARY)
        )
    }

    @Test
    fun `recipes with the same name are kept as separate sources`() {
        val cookies1 = recipe("r1", "Cookies", null to listOf(Ingredient(name = "flour", amount = Amount(1.0, "cup"))))
        val cookies2 = recipe("r2", "Cookies", null to listOf(Ingredient(name = "flour", amount = Amount(2.0, "cup"))))

        val items = useCase.execute(
            listOf(entry("e1", cookies1) to cookies1, entry("e2", cookies2) to cookies2)
        )

        val sources = items.single().sources
        assertEquals(2, sources.size)
        assertEquals(listOf(1.0, 2.0), sources.map { it.ingredient.amount!!.value })
        assertNotEquals(sources[0].key, sources[1].key)
    }

    @Test
    fun `same recipe planned twice sums servings`() {
        val soup = recipe("soup", "Soup", null to listOf(Ingredient(name = "onion", amount = Amount(1.0))))

        val items = useCase.execute(
            listOf(entry("e1", soup, 1.0) to soup, entry("e2", soup, 0.5) to soup)
        )

        val source = items.single().sources.single()
        assertEquals(1.5, source.scale, 0.0001)
    }

    @Test
    fun `incompatible amounts in one recipe become distinct sources`() {
        val cake = recipe(
            "cake", "Cake",
            "Cake" to listOf(Ingredient(name = "butter", amount = Amount(1.0, "cup"))),
            "Frosting" to listOf(Ingredient(name = "butter", amount = Amount(50.0, "g")))
        )

        val sources = useCase.execute(listOf(entry("e1", cake) to cake)).single().sources

        assertEquals(2, sources.size)
        assertNotEquals(sources[0].key, sources[1].key)
    }

    @Test
    fun `plural and singular names are grouped`() {
        val a = recipe("a", "A", null to listOf(Ingredient(name = "chilies", amount = Amount(2.0))))
        val b = recipe("b", "B", null to listOf(Ingredient(name = "chili", amount = Amount(1.0))))

        val items = useCase.execute(listOf(entry("e1", a) to a, entry("e2", b) to b))

        assertEquals(1, items.size)
        assertEquals(2, items.single().sources.size)
    }
}
