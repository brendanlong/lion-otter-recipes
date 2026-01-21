package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Measurement
import com.lionotter.recipes.domain.model.MeasurementType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMarkdownFormatterTest {

    private fun createTestRecipe(
        id: String = "test-id",
        name: String = "Test Recipe",
        sourceUrl: String? = null,
        story: String? = null,
        servings: Int? = null,
        prepTime: String? = null,
        cookTime: String? = null,
        totalTime: String? = null,
        ingredientSections: List<IngredientSection> = emptyList(),
        instructionSections: List<InstructionSection> = emptyList(),
        tags: List<String> = emptyList()
    ) = Recipe(
        id = id,
        name = name,
        sourceUrl = sourceUrl,
        story = story,
        servings = servings,
        prepTime = prepTime,
        cookTime = cookTime,
        totalTime = totalTime,
        ingredientSections = ingredientSections,
        instructionSections = instructionSections,
        tags = tags,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun volumeMeasurement(value: Double, unit: String, isDefault: Boolean = true) =
        Measurement(value = value, unit = unit, type = MeasurementType.VOLUME, isDefault = isDefault)

    private fun weightMeasurement(value: Double, unit: String, isDefault: Boolean = false) =
        Measurement(value = value, unit = unit, type = MeasurementType.WEIGHT, isDefault = isDefault)

    @Test
    fun `format includes recipe title as h1`() {
        val recipe = createTestRecipe(name = "Amazing Pasta")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("# Amazing Pasta"))
    }

    @Test
    fun `format includes source url when present`() {
        val recipe = createTestRecipe(sourceUrl = "https://example.com/recipe")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*Source:"))
        assertTrue(markdown.contains("https://example.com/recipe"))
    }

    @Test
    fun `format excludes source url when not present`() {
        val recipe = createTestRecipe(sourceUrl = null)
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertFalse(markdown.contains("*Source:"))
    }

    @Test
    fun `format includes story when present`() {
        val recipe = createTestRecipe(story = "This is a family recipe passed down through generations.")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("This is a family recipe passed down through generations."))
    }

    @Test
    fun `format includes servings when present`() {
        val recipe = createTestRecipe(servings = 4)
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("**Servings:** 4"))
    }

    @Test
    fun `format includes prep time when present`() {
        val recipe = createTestRecipe(prepTime = "15 minutes")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("**Prep Time:** 15 minutes"))
    }

    @Test
    fun `format includes cook time when present`() {
        val recipe = createTestRecipe(cookTime = "30 minutes")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("**Cook Time:** 30 minutes"))
    }

    @Test
    fun `format includes total time when present`() {
        val recipe = createTestRecipe(totalTime = "45 minutes")
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("**Total Time:** 45 minutes"))
    }

    @Test
    fun `format includes all metadata separated by pipe`() {
        val recipe = createTestRecipe(
            servings = 4,
            prepTime = "10 min",
            cookTime = "20 min"
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        // Check that metadata items are on the same line separated by |
        assertTrue(markdown.contains("**Servings:** 4 | **Prep Time:** 10 min | **Cook Time:** 20 min"))
    }

    @Test
    fun `format includes tags when present`() {
        val recipe = createTestRecipe(tags = listOf("italian", "pasta", "quick"))
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("**Tags:** italian, pasta, quick"))
    }

    @Test
    fun `format excludes tags section when no tags`() {
        val recipe = createTestRecipe(tags = emptyList())
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertFalse(markdown.contains("**Tags:**"))
    }

    @Test
    fun `format includes ingredients section header`() {
        val recipe = createTestRecipe()
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("## Ingredients"))
    }

    @Test
    fun `format shows no ingredients message when empty`() {
        val recipe = createTestRecipe(ingredientSections = emptyList())
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*No ingredients listed*"))
    }

    @Test
    fun `format includes ingredient section names`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    name = "For the Sauce",
                    ingredients = listOf(
                        Ingredient(name = "tomatoes", amounts = listOf(volumeMeasurement(2.0, "cups")))
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("### For the Sauce"))
    }

    @Test
    fun `format lists ingredients with amounts`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(name = "flour", amounts = listOf(volumeMeasurement(2.0, "cups")))
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("- 2 cups flour"))
    }

    @Test
    fun `format includes ingredient notes`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(
                            name = "butter",
                            amounts = listOf(volumeMeasurement(1.0, "cup")),
                            notes = "softened"
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("butter, softened"))
    }

    @Test
    fun `format marks optional ingredients`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(
                            name = "chocolate chips",
                            amounts = listOf(volumeMeasurement(0.5, "cup")),
                            optional = true
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*(optional)*"))
    }

    @Test
    fun `format includes alternate ingredients`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(
                            name = "butter",
                            amounts = listOf(volumeMeasurement(1.0, "cup")),
                            alternates = listOf(
                                Ingredient(
                                    name = "margarine",
                                    amounts = listOf(volumeMeasurement(1.0, "cup"))
                                )
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("OR"))
        assertTrue(markdown.contains("margarine"))
    }

    @Test
    fun `format includes alternate measurements in parentheses`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(
                            name = "flour",
                            amounts = listOf(
                                volumeMeasurement(2.0, "cups", isDefault = true),
                                weightMeasurement(250.0, "grams", isDefault = false)
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        // Default amount shown first, alternate in parentheses
        assertTrue(markdown.contains("2 cups"))
        assertTrue(markdown.contains("(250 grams)"))
    }

    @Test
    fun `format includes instructions section header`() {
        val recipe = createTestRecipe()
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("## Instructions"))
    }

    @Test
    fun `format shows no instructions message when empty`() {
        val recipe = createTestRecipe(instructionSections = emptyList())
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*No instructions listed*"))
    }

    @Test
    fun `format includes instruction section names`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    name = "Make the Dough",
                    steps = listOf(
                        InstructionStep(stepNumber = 1, instruction = "Mix ingredients")
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("### Make the Dough"))
    }

    @Test
    fun `format numbers instruction steps`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(stepNumber = 1, instruction = "Preheat oven to 350F"),
                        InstructionStep(stepNumber = 2, instruction = "Mix dry ingredients"),
                        InstructionStep(stepNumber = 3, instruction = "Add wet ingredients")
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("1. Preheat oven to 350F"))
        assertTrue(markdown.contains("2. Mix dry ingredients"))
        assertTrue(markdown.contains("3. Add wet ingredients"))
    }

    @Test
    fun `format marks optional instruction steps`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Add chocolate chips",
                            optional = true
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*(optional)*"))
    }

    @Test
    fun `format converts fractions correctly`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(name = "butter", amounts = listOf(volumeMeasurement(0.5, "cup"))),
                        Ingredient(name = "sugar", amounts = listOf(volumeMeasurement(0.25, "cup"))),
                        Ingredient(name = "milk", amounts = listOf(volumeMeasurement(0.75, "cup"))),
                        Ingredient(name = "cream", amounts = listOf(volumeMeasurement(0.33, "cup"))),
                        Ingredient(name = "oil", amounts = listOf(volumeMeasurement(0.66, "cup")))
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("1/2 cup butter"))
        assertTrue(markdown.contains("1/4 cup sugar"))
        assertTrue(markdown.contains("3/4 cup milk"))
        assertTrue(markdown.contains("1/3 cup cream"))
        assertTrue(markdown.contains("2/3 cup oil"))
    }

    @Test
    fun `format handles mixed numbers`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(name = "flour", amounts = listOf(volumeMeasurement(2.5, "cups")))
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("2 1/2 cups flour"))
    }

    @Test
    fun `format handles whole numbers`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(name = "eggs", amounts = listOf(volumeMeasurement(3.0, "large")))
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("3 large eggs"))
    }

    @Test
    fun `format includes horizontal rule separator`() {
        val recipe = createTestRecipe()
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("---"))
    }

    @Test
    fun `format handles ingredient with null value measurement`() {
        val recipe = createTestRecipe(
            ingredientSections = listOf(
                IngredientSection(
                    ingredients = listOf(
                        Ingredient(
                            name = "salt",
                            amounts = listOf(
                                Measurement(value = null, unit = "to taste", type = MeasurementType.VOLUME, isDefault = true)
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("to taste"))
    }

    @Test
    fun `format complete recipe with all sections`() {
        val recipe = Recipe(
            id = "complete-recipe",
            name = "Complete Test Recipe",
            sourceUrl = "https://example.com/recipe",
            story = "A test recipe for unit testing",
            servings = 4,
            prepTime = "15 min",
            cookTime = "30 min",
            totalTime = "45 min",
            ingredientSections = listOf(
                IngredientSection(
                    name = "Main Ingredients",
                    ingredients = listOf(
                        Ingredient(name = "flour", amounts = listOf(volumeMeasurement(2.0, "cups"))),
                        Ingredient(name = "sugar", amounts = listOf(volumeMeasurement(1.0, "cup")))
                    )
                )
            ),
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(stepNumber = 1, instruction = "Mix flour and sugar"),
                        InstructionStep(stepNumber = 2, instruction = "Bake at 350F")
                    )
                )
            ),
            tags = listOf("baking", "dessert"),
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000)
        )

        val markdown = RecipeMarkdownFormatter.format(recipe)

        // Verify structure
        assertTrue(markdown.contains("# Complete Test Recipe"))
        assertTrue(markdown.contains("*Source:"))
        assertTrue(markdown.contains("A test recipe for unit testing"))
        assertTrue(markdown.contains("**Servings:** 4"))
        assertTrue(markdown.contains("**Tags:** baking, dessert"))
        assertTrue(markdown.contains("## Ingredients"))
        assertTrue(markdown.contains("### Main Ingredients"))
        assertTrue(markdown.contains("- 2 cups flour"))
        assertTrue(markdown.contains("## Instructions"))
        assertTrue(markdown.contains("1. Mix flour and sugar"))
    }
}
