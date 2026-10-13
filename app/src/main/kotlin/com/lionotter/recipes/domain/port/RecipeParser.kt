package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.InstructionSection

/**
 * Turns recipe text (a web page's extracted content, Markdown, a Paprika recipe), or photos or a
 * PDF of a recipe, into a recipe with AI.
 */
interface RecipeParser {
    /**
     * Fails with [RecipeError.Ai][com.lionotter.recipes.domain.RecipeError.Ai] when the request or
     * its response is unusable, or [RecipeError.NotARecipe][com.lionotter.recipes.domain.RecipeError.NotARecipe]
     * when the AI decides the content isn't a recipe.
     */
    suspend fun parse(request: ParseRequest): Outcome<ParsedRecipeWithUsage>
}

data class ParseRequest(
    /** The recipe's text; empty when the recipe is in [attachments]. */
    val content: String,
    val apiKey: String,
    val model: String,
    val thinkingEnabled: Boolean,
    /** Densities already known for this recipe, preferred over the default table. */
    val densityOverrides: Map<String, Double>? = null,
    /** The user's own instructions, e.g. "Make this recipe vegan". */
    val aiInstructions: String? = null,
    /** Tags the user already has (most used first), for the AI to reuse. */
    val existingTags: List<String> = emptyList(),
    /** Photos (in page order) or a PDF of the recipe. */
    val attachments: List<ParseAttachment> = emptyList()
)

/** A file sent to the AI with a [ParseRequest]. */
sealed class ParseAttachment(val bytes: ByteArray, val mediaType: String) {
    /** A photo; [mediaType] is `image/jpeg`, `image/png`, `image/gif` or `image/webp`. */
    class Image(bytes: ByteArray, mediaType: String) : ParseAttachment(bytes, mediaType)

    class Pdf(bytes: ByteArray) : ParseAttachment(bytes, PDF_MEDIA_TYPE)

    override fun toString(): String = "${this::class.simpleName}($mediaType, ${bytes.size} bytes)"

    companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
    }
}

/** The AI-generated content of a recipe. */
data class ParsedRecipe(
    val name: String,
    val story: String? = null,
    val servings: Int? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val instructionSections: List<InstructionSection>,
    val equipment: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

data class ParsedRecipeWithUsage(
    val recipe: ParsedRecipe,
    val inputTokens: Long,
    val outputTokens: Long,
    /** The AI's JSON response, kept for import debugging. */
    val aiOutputJson: String
)
