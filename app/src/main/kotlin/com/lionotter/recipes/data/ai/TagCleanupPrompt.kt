package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.util.ErrorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The prompt and structured-output schema for suggesting tag cleanups. */
object TagCleanupPrompt {
    private const val TAG = "TagCleanupPrompt"

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

    /** The changes in a response's [text]; failures are attributed to [provider]. */
    fun parseResponse(json: Json, text: String, provider: String): Outcome<List<TagMapping>> {
        if (text.isBlank()) return Outcome.Failure(RecipeError.Ai(AiFailure.EMPTY_RESPONSE, provider = provider))
        return try {
            val response = json.decodeFromString<TagCleanupResponse>(text.trim())
            Outcome.Success(response.changes.map { TagMapping(it.from, it.to) })
        } catch (e: IllegalArgumentException) {
            ErrorLog.e(TAG, "Failed to parse the tag cleanup response", e)
            Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE, provider = provider))
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
