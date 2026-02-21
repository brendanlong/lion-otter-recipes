package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import kotlin.time.Instant
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
        instructionSections = instructionSections,
        tags = tags,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

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
        val recipe = createTestRecipe(instructionSections = emptyList())
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("*No ingredients listed*"))
    }

    @Test
    fun `format includes instruction section names in ingredients`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    name = "For the Sauce",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Cook tomatoes",
                            ingredients = listOf(
                                Ingredient(name = "tomatoes", amount = Amount(value = 2.0, unit = "cup"), density = 0.96)
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("### For the Sauce"))
    }

    @Test
    fun `format lists ingredients with amounts from steps`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"), density = 0.51)
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("- 2 cup flour"))
    }

    @Test
    fun `format includes ingredient notes`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(
                                    name = "butter",
                                    amount = Amount(value = 1.0, unit = "cup"),
                                    density = 0.96,
                                    notes = "softened"
                                )
                            )
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
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Top",
                            ingredients = listOf(
                                Ingredient(
                                    name = "chocolate chips",
                                    amount = Amount(value = 0.5, unit = "cup"),
                                    density = 0.72,
                                    optional = true
                                )
                            )
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
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Cream butter",
                            ingredients = listOf(
                                Ingredient(
                                    name = "butter",
                                    amount = Amount(value = 1.0, unit = "cup"),
                                    density = 0.96,
                                    alternates = listOf(
                                        Ingredient(
                                            name = "margarine",
                                            amount = Amount(value = 1.0, unit = "cup"),
                                            density = 0.96
                                        )
                                    )
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
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(name = "butter", amount = Amount(value = 0.5, unit = "cup"), density = 0.96),
                                Ingredient(name = "sugar", amount = Amount(value = 0.25, unit = "cup"), density = 0.84),
                                Ingredient(name = "milk", amount = Amount(value = 0.75, unit = "cup"), density = 0.96),
                                Ingredient(name = "cream", amount = Amount(value = 0.33, unit = "cup"), density = 0.96),
                                Ingredient(name = "oil", amount = Amount(value = 0.66, unit = "cup"), density = 0.84)
                            )
                        )
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
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.5, unit = "cup"), density = 0.51)
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("2 1/2 cup flour"))
    }

    @Test
    fun `format handles whole numbers`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Add eggs",
                            ingredients = listOf(
                                Ingredient(name = "eggs", amount = Amount(value = 3.0))
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("3 eggs"))
    }

    @Test
    fun `format includes horizontal rule separator`() {
        val recipe = createTestRecipe()
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("---"))
    }

    @Test
    fun `format handles ingredient with no amount`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Season",
                            ingredients = listOf(
                                Ingredient(
                                    name = "salt",
                                    notes = "to taste"
                                )
                            )
                        )
                    )
                )
            )
        )
        val markdown = RecipeMarkdownFormatter.format(recipe)

        assertTrue(markdown.contains("salt"))
    }

    @Test
    fun `formatBody excludes title and source url`() {
        val recipe = createTestRecipe(
            name = "My Recipe",
            sourceUrl = "https://example.com/recipe",
            story = "A great recipe"
        )
        val body = RecipeMarkdownFormatter.formatBody(recipe)

        assertFalse(body.contains("# My Recipe"))
        assertFalse(body.contains("*Source:"))
        assertTrue(body.contains("A great recipe"))
    }

    @Test
    fun `format includes title and url while formatBody does not`() {
        val recipe = createTestRecipe(
            name = "My Recipe",
            sourceUrl = "https://example.com/recipe",
            story = "A great recipe"
        )
        val full = RecipeMarkdownFormatter.format(recipe)
        val body = RecipeMarkdownFormatter.formatBody(recipe)

        assertTrue(full.contains("# My Recipe"))
        assertTrue(full.contains("*Source:"))
        assertFalse(body.contains("# My Recipe"))
        assertFalse(body.contains("*Source:"))
        // Body content should be present in both
        assertTrue(full.contains("A great recipe"))
        assertTrue(body.contains("A great recipe"))
    }

    @Test
    fun `collectDensities returns densities from all ingredients`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"), density = 0.51),
                                Ingredient(name = "sugar", amount = Amount(value = 1.0, unit = "cup"), density = 0.84),
                                Ingredient(name = "eggs", amount = Amount(value = 3.0))
                            )
                        )
                    )
                )
            )
        )

        val densities = RecipeMarkdownFormatter.collectDensities(recipe)

        assertTrue(densities.containsKey("flour"))
        assertTrue(densities.containsKey("sugar"))
        assertFalse(densities.containsKey("eggs"))
        assertTrue(densities["flour"] == 0.51)
        assertTrue(densities["sugar"] == 0.84)
    }

    @Test
    fun `collectDensities includes alternate ingredients`() {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Cream butter",
                            ingredients = listOf(
                                Ingredient(
                                    name = "butter",
                                    amount = Amount(value = 1.0, unit = "cup"),
                                    density = 0.96,
                                    alternates = listOf(
                                        Ingredient(
                                            name = "margarine",
                                            amount = Amount(value = 1.0, unit = "cup"),
                                            density = 0.88
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val densities = RecipeMarkdownFormatter.collectDensities(recipe)

        assertTrue(densities.containsKey("butter"))
        assertTrue(densities.containsKey("margarine"))
        assertTrue(densities["margarine"] == 0.88)
    }

    @Test
    fun `DEFAULT_DENSITIES contains common baking ingredients`() {
        val defaults = RecipeMarkdownFormatter.DEFAULT_DENSITIES
        assertTrue(defaults.containsKey("all-purpose flour"))
        assertTrue(defaults.containsKey("butter"))
        assertTrue(defaults.containsKey("granulated sugar"))
        assertTrue(defaults.containsKey("water"))
        assertTrue(defaults["all-purpose flour"] == 0.51)
        assertTrue(defaults["butter"] == 0.96)
    }

    @Test
    fun `formatDensityHints returns empty string for empty densities`() {
        val hints = RecipeMarkdownFormatter.formatDensityHints(emptyMap())
        assertTrue(hints.isEmpty())
    }

    @Test
    fun `formatDensityHints formats densities correctly`() {
        val densities = mapOf("flour" to 0.51, "sugar" to 0.84)
        val hints = RecipeMarkdownFormatter.formatDensityHints(densities)

        assertTrue(hints.contains("flour 0.51"))
        assertTrue(hints.contains("sugar 0.84"))
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
            instructionSections = listOf(
                InstructionSection(
                    name = "Main Ingredients",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix flour and sugar",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"), density = 0.51),
                                Ingredient(name = "sugar", amount = Amount(value = 1.0, unit = "cup"), density = 0.84)
                            )
                        ),
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
        assertTrue(markdown.contains("- 2 cup flour"))
        assertTrue(markdown.contains("## Instructions"))
        assertTrue(markdown.contains("1. Mix flour and sugar"))
    }
}
