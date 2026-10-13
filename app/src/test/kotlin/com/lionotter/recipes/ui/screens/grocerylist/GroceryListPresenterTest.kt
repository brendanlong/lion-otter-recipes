package com.lionotter.recipes.ui.screens.grocerylist

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class GroceryListPresenterTest {

    private val presenter = GroceryListPresenter

    private fun recipe(id: String, vararg ingredients: Ingredient) = Recipe(
        id = id,
        name = "Recipe $id",
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(InstructionStep(stepNumber = 1, instruction = "Mix", ingredients = ingredients.toList()))
            )
        ),
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun entry(recipe: Recipe) = MealPlanEntry(
        id = "e-${recipe.id}",
        recipeId = recipe.id,
        recipeName = recipe.name,
        recipeImageUrl = null,
        date = LocalDate(2025, 1, 1),
        mealType = MealType.DINNER,
        servings = 1.0,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun display(vararg recipes: Recipe, checkedSources: Set<String> = emptySet()): List<DisplayGroceryItem> =
        presenter.displayItems(
            AggregateGroceryListUseCase().execute(recipes.map { entry(it) to it }),
            checkedSources,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

    private fun sugar(value: Double, unit: String, density: Double? = null) =
        Ingredient(name = "sugar", amount = Amount(value = value, unit = unit), density = density)

    @Test
    fun `counts and measured amounts are both shown`() {
        val total = display(
            recipe("a", Ingredient(name = "onion", amount = Amount(value = 1.0))),
            recipe("b", Ingredient(name = "onion", amount = Amount(value = 200.0, unit = "g")))
        ).single().totalAmount!!

        assertTrue("Expected grams and count but got: $total", total.contains("g") && total.endsWith("+ 1"))
    }

    @Test
    fun `merged items show modifiers on source lines but not in the header`() {
        val item = display(
            recipe("a", Ingredient(name = "apple", amount = Amount(value = 2.0), modifier = "peeled")),
            recipe("b", Ingredient(name = "apples", amount = Amount(value = 1.0), modifier = "chopped"))
        ).single()

        assertEquals("3 apple", presenter.aggregateHeaderText(item, "+ more"))
        assertEquals(listOf("2 apple, peeled", "1 apples, chopped"), item.sources.map { it.displayText })
    }

    @Test
    fun `sources without an amount mark the total as partial`() {
        val item = display(
            recipe("a", Ingredient(name = "salt", amount = Amount(value = 1.0, unit = "tsp"))),
            recipe("b", Ingredient(name = "salt"))
        ).single()

        assertTrue(item.totalIsPartial)
        assertTrue(presenter.aggregateHeaderText(item, "+ more").endsWith("salt + more"))
    }

    @Test
    fun `same unit category sources are summed`() {
        val total = display(recipe("a", sugar(2.0, "tbsp")), recipe("b", sugar(3.0, "tbsp"))).single().totalAmount!!
        assertTrue("Expected a volume unit but got: $total", total.contains("tbsp") || total.contains("tsp"))
    }

    @Test
    fun `mixed weight and volume sources are converted to weight`() {
        val total = display(
            recipe("a", sugar(11.0, "tbsp", density = 0.85)),
            recipe("b", sugar(10.0, "oz", density = 0.85))
        ).single().totalAmount!!
        assertTrue("Expected grams but got: $total", total.contains("g"))
    }

    @Test
    fun `mixed categories use a shared density for sources missing it`() {
        val total = display(
            recipe("a", sugar(5.0, "tbsp")),
            recipe("b", sugar(10.0, "oz", density = 0.85))
        ).single().totalAmount!!
        assertTrue("Expected grams but got: $total", total.contains("g"))
    }

    @Test
    fun `single source has no total`() {
        assertNull(display(recipe("a", sugar(2.0, "cup"))).single().totalAmount)
    }

    @Test
    fun `counts are summed`() {
        val items = display(
            recipe("a", Ingredient(name = "eggs", amount = Amount(value = 3.0))),
            recipe("b", Ingredient(name = "eggs", amount = Amount(value = 2.0)))
        )
        assertEquals("5", items.single().totalAmount)
    }

    @Test
    fun `an item is checked exactly when all of its sources are`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp")), recipe("b", sugar(3.0, "tbsp")))
        val item = display(*recipes).single()
        val (first, second) = item.sources.map { it.key }

        val one = display(*recipes, checkedSources = setOf(first)).single()
        assertFalse(one.isChecked)
        assertTrue(one.sources.first { it.key == first }.isChecked)

        assertTrue(display(*recipes, checkedSources = setOf(first, second)).single().isChecked)
    }

    @Test
    fun `checked sources are left out of the total`() {
        val recipes = arrayOf(
            recipe("a", Ingredient(name = "eggs", amount = Amount(value = 3.0))),
            recipe("b", Ingredient(name = "eggs", amount = Amount(value = 2.0)))
        )
        val sourceA = display(*recipes).single().sources.first { it.recipeName == "Recipe a" }.key

        assertEquals("2", display(*recipes, checkedSources = setOf(sourceA)).single().totalAmount)
    }

    @Test
    fun `checking and unchecking an item sets all of its sources`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp")), recipe("b", sugar(3.0, "tbsp")))
        val item = display(*recipes).single()

        val checked = presenter.setItemChecked(setOf("unrelated"), item, checked = true)
        assertEquals(item.sourceKeys + "unrelated", checked)
        assertTrue(display(*recipes, checkedSources = checked).single().sources.all { it.isChecked })

        val unchecked = presenter.setItemChecked(checked, item, checked = false)
        assertEquals(setOf("unrelated"), unchecked)
    }

    @Test
    fun `unchecking one source unchecks the item`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp")), recipe("b", sugar(3.0, "tbsp")))
        val item = display(*recipes).single()
        val all = presenter.setItemChecked(emptySet(), item, checked = true)

        val after = presenter.setSourceChecked(all, item.sources[0].key, checked = false)

        val shown = display(*recipes, checkedSources = after).single()
        assertFalse(shown.isChecked)
        assertEquals(1, shown.sources.count { it.isChecked })
    }

    @Test
    fun `keys of sources no longer on the list are ignored`() {
        val items = display(recipe("a", sugar(2.0, "tbsp")), checkedSources = setOf("flour_gone"))
        assertFalse(items.single().isChecked)
    }

    @Test
    fun `checked items sort after unchecked ones`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp"), Ingredient(name = "flour")))
        val sugarItem = display(*recipes).first { it.key == "sugar" }

        val items = display(*recipes, checkedSources = sugarItem.sourceKeys)

        assertEquals(listOf("flour", "sugar"), items.map { it.key })
    }

    @Test
    fun `share text lists only unchecked sources`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp")), recipe("b", sugar(3.0, "tbsp")))
        val sourceA = display(*recipes).single().sources.first { it.recipeName == "Recipe a" }.key

        val text = presenter.shareText(display(*recipes, checkedSources = setOf(sourceA)), "+ more")

        assertTrue(text.startsWith("# Grocery List"))
        assertTrue(text.contains("Recipe b"))
        assertFalse(text.contains("Recipe a"))
    }

    @Test
    fun `share text shows a header and sub-items for several unchecked sources`() {
        val items = display(
            recipe("a", Ingredient(name = "eggs", amount = Amount(value = 3.0))),
            recipe("b", Ingredient(name = "eggs", amount = Amount(value = 2.0)))
        )

        val lines = presenter.shareText(items, "+ more").lines()

        assertEquals("- [ ] 5 eggs", lines[2])
        assertTrue(lines[3].startsWith("  - [ ] ") && lines[3].endsWith("(Recipe a)"))
        assertTrue(lines[4].startsWith("  - [ ] ") && lines[4].endsWith("(Recipe b)"))
    }

    @Test
    fun `fully checked items are left out of the share text`() {
        val recipes = arrayOf(recipe("a", sugar(2.0, "tbsp")))
        val item = display(*recipes).single()

        val text = presenter.shareText(display(*recipes, checkedSources = item.sourceKeys), "+ more")

        assertEquals("# Grocery List", text)
    }
}
