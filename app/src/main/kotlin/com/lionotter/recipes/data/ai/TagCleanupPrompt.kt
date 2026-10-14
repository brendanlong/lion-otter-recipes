package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.model.TagUsage
import kotlinx.serialization.Serializable

/** The prompt and structured-output schema for suggesting tag cleanups. */
object TagCleanupPrompt {
    val systemPrompt = """
You tidy up the tags of a personal recipe collection so they work well as filters.

You get every tag with the number of recipes that use it. Suggest changes:
- Write every tag in Title Case and in the singular, e.g. "Dessert", "Main Course", "Chocolate Chip Cookie", "Gluten-Free". Rename any tag that doesn't follow this style, even if most of the other tags don't either.
- Merge duplicates and near-duplicates (different case or spelling, singular/plural, synonyms, e.g. "Desserts", "dessert" and "sweets") into one tag. Prefer the most used or clearest name, written in the style above.
- Delete tags that don't help find recipes, such as "recipe", "food", "homemade", "easy recipe" or a tag that just repeats a recipe's name.
- Keep specific, useful tags (a cuisine, a main ingredient, a diet, a course, a cooking method), even if only one recipe uses them.
- Don't invent new categories or split tags; only rename, merge into, or delete the tags you are given.

Return only the tags that should change, each with "from" set to the tag exactly as given and "to" set to its new name, or to null to delete it. To merge tags, give each of them the same "to". Leave out tags that stay as they are. If nothing needs to change, return an empty list.
""".trim()

    fun buildUserMessage(tags: List<TagUsage>): String = buildString {
        append("Recipes\tTag\n")
        for (tag in tags) {
            append(tag.recipeCount).append('\t').append(tag.name).append('\n')
        }
    }

    /** JSON schema for [TagCleanupResponse]. */
    val RESPONSE_SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "changes" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "from" to mapOf("type" to "string"),
                        "to" to mapOf("type" to listOf("string", "null"))
                    ),
                    "required" to listOf("from", "to"),
                    "additionalProperties" to false
                )
            )
        ),
        "required" to listOf("changes"),
        "additionalProperties" to false
    )
}

@Serializable
data class TagCleanupResponse(val changes: List<TagCleanupChange>)

@Serializable
data class TagCleanupChange(val from: String, val to: String?)
