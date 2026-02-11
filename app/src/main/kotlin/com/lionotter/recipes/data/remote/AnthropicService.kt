package com.lionotter.recipes.data.remote

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exception thrown when the AI cannot parse the recipe content.
 * Contains a human-readable error message from the AI.
 */
class RecipeParseException(message: String) : Exception(message)

data class ParseResultWithUsage(
    val result: RecipeParseResult,
    val inputTokens: Long,
    val outputTokens: Long,
    val aiOutputJson: String
)

@Singleton
class AnthropicService @Inject constructor(
    private val json: Json
) {
    suspend fun parseRecipe(
        html: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        extendedThinking: Boolean = true
    ): Result<ParseResultWithUsage> {
        return try {
            val client = buildClient(apiKey)

            val paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000)
                .systemOfTextBlockParams(
                    listOf(
                        TextBlockParam.builder()
                            .text(SYSTEM_PROMPT)
                            .build()
                    )
                )
                .addUserMessage("Parse this recipe webpage and extract the structured data:\n\n$html")

            if (extendedThinking) {
                paramsBuilder.thinking(
                    ThinkingConfigEnabled.builder()
                        .budgetTokens(8000)
                        .build()
                )
            }

            val params = paramsBuilder.build()

            // The SDK uses OkHttp synchronously, so run on IO dispatcher
            val message = withContext(Dispatchers.IO) {
                client.messages().create(params)
            }

            // Extract token usage
            val usage = message.usage()
            val inputTokens = usage.inputTokens()
            val outputTokens = usage.outputTokens()

            // Find the text content block (skip thinking blocks)
            val textContent = message.content()
                .firstOrNull { it.isText() }
                ?.asText()
                ?.text()
                ?: return Result.failure(Exception("No text content in response"))

            // Extract JSON from the response (it might be wrapped in markdown code blocks)
            val jsonContent = extractJson(textContent)

            val parseResponse = json.decodeFromString(RecipeParseResponse.serializer(), jsonContent)

            if (!parseResponse.success) {
                val errorMessage = parseResponse.error ?: "Failed to parse recipe"
                return Result.failure(RecipeParseException(errorMessage))
            }

            val recipeResult = parseResponse.recipe
                ?: return Result.failure(RecipeParseException("No recipe data in response"))

            Result.success(ParseResultWithUsage(
                result = recipeResult,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                aiOutputJson = jsonContent
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildClient(apiKey: String): AnthropicClient {
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
    }

    private fun extractJson(content: String): String {
        // Try to extract JSON from markdown code blocks
        val jsonBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = jsonBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // If no code block, try to find JSON object directly
        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return content.substring(jsonStart, jsonEnd + 1)
        }

        // Return as-is and let JSON parser handle it
        return content
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-4-6"
        private const val API_KEY_PREFIX = "sk-ant-"

        /**
         * Validates an Anthropic API key format.
         * Returns null if valid, or an error message if invalid.
         */
        fun validateApiKey(apiKey: String): String? {
            val trimmed = apiKey.trim()
            return when {
                trimmed.isBlank() -> "API key cannot be empty"
                !trimmed.startsWith(API_KEY_PREFIX) -> "Invalid API key format. Should start with '$API_KEY_PREFIX'"
                else -> null
            }
        }

        /**
         * Returns true if the API key has a valid format.
         */
        fun isValidApiKey(apiKey: String): Boolean = validateApiKey(apiKey) == null

        private val SYSTEM_PROMPT = """
You are a recipe parser. Extract structured recipe data from HTML content and return it as JSON.

Return ONLY valid JSON (no markdown, no explanations). Your response must always be wrapped in a success/error structure.

For successful parsing, return:
{
  "success": true,
  "recipe": {
    "name": "Recipe Name",
    "story": "Brief summary (1-2 sentences)",
    "servings": 4,
    "prepTime": "15 minutes",
    "cookTime": "30 minutes",
    "totalTime": "45 minutes",
    "instructionSections": [
      {
        "name": "Make the cake",
        "steps": [
          {
            "stepNumber": 1,
            "instruction": "Preheat oven to 350°F."
          },
          {
            "stepNumber": 2,
            "instruction": "Mix dry ingredients together.",
            "ingredients": [
              { "name": "all-purpose flour", "amount": { "value": 2, "unit": "cup" }, "density": 0.51 },
              { "name": "granulated sugar", "amount": { "value": 1, "unit": "cup" }, "density": 0.84 },
              { "name": "baking powder", "amount": { "value": 1, "unit": "tsp" }, "density": 0.81 }
            ]
          },
          {
            "stepNumber": 3,
            "instruction": "Add wet ingredients and mix until combined.",
            "ingredients": [
              { "name": "butter", "amount": { "value": 8, "unit": "oz" }, "density": 0.96, "notes": "melted" },
              { "name": "eggs", "amount": { "value": 3 }, "notes": "room temperature" },
              { "name": "milk", "amount": { "value": 1, "unit": "cup" }, "density": 0.96 },
              {
                "name": "kosher salt",
                "amount": { "value": 1, "unit": "tsp" },
                "density": 0.54,
                "alternates": [
                  { "name": "table salt", "amount": { "value": 0.5, "unit": "tsp" }, "density": 1.22 }
                ]
              }
            ]
          },
          {
            "stepNumber": 5,
            "instruction": "Form into a ball, coat with olive oil, and place in a covered bowl.",
            "yields": 2,
            "ingredients": [
              { "name": "olive oil", "amount": { "value": 1, "unit": "tsp" }, "density": 0.84 }
            ]
          }
        ]
      }
    ],
    "equipment": ["9-inch round cake pan", "stand mixer", "wire cooling rack"],
    "tags": ["dessert", "cake", "baking"]
  }
}

If you cannot parse the content as a recipe, return an error response:
{
  "success": false,
  "error": "Human-readable explanation of why parsing failed"
}

Return an error response when:
- The page does not contain a recipe (e.g., it's a blog post, article, or other non-recipe content)
- The content is too incomplete to extract a meaningful recipe (missing ingredients or instructions)
- The content is garbled, corrupted, or not in a readable format
- You cannot confidently identify the recipe name, ingredients, or instructions

INGREDIENT FORMAT:
- Each ingredient has a single "amount" object with "value" (number, integer or decimal) and "unit" (string).
- For countable items (eggs, lemons, cloves), omit the "unit" field — just include "value".
- ALWAYS include a "density" field (g/mL) for ANY ingredient measured by weight or volume. The app uses density to let users convert between weight and volume, so density is required regardless of which unit the recipe uses. For example, "8 oz butter" still needs "density": 0.96 so the app can show the equivalent volume.
- Only omit "density" for countable items (no unit) or ingredients with no meaningful density (e.g., "a pinch of saffron threads").
- If the recipe provides both weight and volume, prefer weight.
- Use the density reference table below. For ingredients not listed, estimate a reasonable density — cooking precision doesn't require exactness.

SUPPORTED UNITS (use exactly these strings):
- Weight: mg, g, kg, oz, lb
- Volume: mL, L, tsp, tbsp, cup, fl_oz, pint, quart, gal
- Count: omit unit field

INGREDIENT DENSITIES (g/mL — use these when known):
water 0.96, milk 0.96, buttermilk 0.96, heavy cream 0.96, yogurt 0.96, sour cream 0.96,
vegetable oil 0.84, olive oil 0.84, coconut oil 0.96, butter 0.96,
lard 0.96, vegetable shortening 0.78,
honey 1.42, molasses 1.44, corn syrup 1.32, maple syrup 1.32,
all-purpose flour 0.51, bread flour 0.51, cake flour 0.51, whole wheat flour 0.48,
pastry flour 0.45, almond flour 0.41, coconut flour 0.54, rye flour 0.45,
cornmeal 0.58, cornstarch 0.47, cocoa powder 0.35, tapioca starch 0.48,
potato starch 0.64,
granulated sugar 0.84, brown sugar (packed) 0.90, confectioners sugar 0.48,
demerara sugar 0.93, turbinado sugar 0.76,
table salt 1.22, kosher salt (Diamond Crystal) 0.54, kosher salt (Morton's) 1.08,
baking powder 0.81, baking soda 1.22,
peanut butter 1.14, cream cheese 0.96,
oats (old-fashioned) 0.38, oats (rolled) 0.48,
chocolate chips 0.72, walnuts (chopped) 0.48, pecans (chopped) 0.48,
breadcrumbs (dried) 0.47, panko 0.21,
vanilla extract 0.95, espresso powder 0.47

OMIT NULL/EMPTY/DEFAULT FIELDS:
- Do NOT include fields with null values, empty arrays, or default values.
- "optional" defaults to false — omit when false.
- "yields" defaults to 1 — omit when 1.
- "alternates" defaults to empty — omit when empty.
- "notes" defaults to null — omit when null.
- Section "name" defaults to null — omit for single-section recipes.

YIELDS FIELD:
- If a step is performed multiple times (e.g., "form 2 dough balls"), set "yields" to the count (e.g., 2).
- Ingredient amounts are per-iteration; the app multiplies by yields for totals.
- Default is 1 — omit if the step is done once.

INGREDIENTS LIVE ON STEPS ONLY:
- There is NO global ingredient list. All ingredients belong to the step that uses them.
- If an ingredient is split across steps (e.g., 1/2 cup water in step 1 and 1/2 cup water in step 3), list it on each step separately with its per-step amount. The app aggregates totals.
- Include the same ingredient name consistently across steps for correct aggregation.

EQUIPMENT:
- Extract any equipment, tools, or appliances mentioned in the recipe into the "equipment" array.
- Each item is a simple string (e.g., "9-inch round cake pan", "stand mixer", "parchment paper").
- Include sizes/specifications when mentioned (e.g., "12-inch skillet", "8x8-inch baking dish").
- Only include equipment explicitly mentioned or clearly required by the recipe.
- Omit basic items everyone has (e.g., bowls, spoons, measuring cups) unless a specific type is called for.
- Omit the "equipment" field entirely if no notable equipment is mentioned.

ADDITIONAL GUIDELINES:
- If the recipe has distinct sections (e.g., cake and frosting), create separate instructionSections.
- Extract quantities as numbers: use integers for whole amounts (e.g., 1, 2, 3) and decimals for fractions (e.g., 0.5 for 1/2, 0.25 for 1/4).
- Include "notes" for ingredient modifications like "room temperature", "divided", etc. Notes must NOT repeat information already captured in the "name", "amount", or "unit" fields (e.g., if the amount is 1 with no unit, do not put "1 standard ice cube" in notes — use "standard ice cube" or omit notes if no extra info).
- For ingredient alternates/substitutes (indicated by "or" in the recipe): extract the first option as the main ingredient, subsequent options as "alternates" array items.
- For ingredients marked "to taste", "as needed", or similar: omit "amount" entirely and put the phrase in "notes".
- Rephrase instruction text to remove quantity mentions — quantities are shown separately from the ingredient list. Example: "Add 2 cups flour" → instruction: "Add flour", with amount on the ingredient.
- Mark optional ingredients and steps with "optional": true (for garnish, decorative, "if desired" items).
- Generate relevant tags based on recipe type, cuisine, dietary restrictions, etc.
- Keep the story brief — just the essence of any background provided.
- Return null for fields that aren't present in the source.
""".trimIndent()
    }
}
