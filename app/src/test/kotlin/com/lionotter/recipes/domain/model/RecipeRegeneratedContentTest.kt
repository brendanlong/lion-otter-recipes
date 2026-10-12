package com.lionotter.recipes.domain.model

import com.lionotter.recipes.testutil.DomainFixtures
import com.lionotter.recipes.testutil.propertyFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class RecipeRegeneratedContentTest {

    private val existing = DomainFixtures.fullRecipe

    /** Differs from [existing] in every field (checked below), as an AI re-parse would. */
    private val parsed = Recipe(
        id = "parsed-id",
        name = "Parsed Pie",
        sourceUrl = "https://example.com/other",
        story = "Another story",
        servings = 4,
        prepTime = "10 min",
        cookTime = "30 min",
        totalTime = "40 min",
        instructionSections = listOf(
            InstructionSection(steps = listOf(InstructionStep(stepNumber = 1, instruction = "Bake.")))
        ),
        equipment = listOf("oven"),
        tags = listOf("pie"),
        imageUrl = "file:///parsed.jpg",
        sourceImageUrl = "https://example.com/parsed.jpg",
        createdAt = Instant.fromEpochSeconds(1),
        updatedAt = Instant.fromEpochSeconds(2),
        isFavorite = false,
        userNotes = "parsed notes"
    )

    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun `fixtures differ in every field`() {
        val same = propertyFields(Recipe::class.java).filter { it.get(existing) == it.get(parsed) }.map { it.name }
        assertTrue("Give `parsed` a different value for: $same", same.isEmpty())
    }

    @Test
    fun `takes only AI-owned fields from the parsed recipe`() {
        val result = existing.withRegeneratedContent(parsed, now)

        for (field in propertyFields(Recipe::class.java)) {
            val expected = when (field.name) {
                "updatedAt" -> now
                in AI_OWNED_FIELDS -> field.get(parsed)
                else -> field.get(existing)
            }
            assertEquals("Recipe.${field.name}", expected, field.get(result))
        }
    }

    private companion object {
        /** Fields the AI regenerates. Every other field (including new ones) must be preserved. */
        val AI_OWNED_FIELDS = setOf(
            "name", "story", "servings", "prepTime", "cookTime", "totalTime",
            "instructionSections", "equipment", "tags"
        )
    }
}
