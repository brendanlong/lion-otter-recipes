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
    "story": "Brief summary of the recipe's story/background (1-2 sentences, or null if none)",
    "servings": 4,
    "prepTime": "15 minutes",
    "cookTime": "30 minutes",
    "totalTime": "45 minutes",
    "ingredientSections": [
      {
        "name": "For the cake",
        "ingredients": [
          {
            "name": "all-purpose flour",
            "notes": "sifted",
            "alternates": [],
            "amounts": [
              {"value": 2.0, "unit": "cups", "type": "volume", "isDefault": true},
              {"value": 250.0, "unit": "grams", "type": "weight", "isDefault": false}
            ]
          },
          {
            "name": "eggs",
            "notes": "room temperature",
            "alternates": [],
            "amounts": [
              {"value": 3.0, "unit": "large", "type": "count", "isDefault": true}
            ]
          },
          {
            "name": "kosher salt",
            "notes": null,
            "alternates": [
              {
                "name": "table salt",
                "notes": null,
                "alternates": [],
                "amounts": [
                  {"value": 0.5, "unit": "teaspoons", "type": "volume", "isDefault": true}
                ],
                "optional": false
              }
            ],
            "amounts": [
              {"value": 1.0, "unit": "teaspoons", "type": "volume", "isDefault": true},
              {"value": 6.0, "unit": "grams", "type": "weight", "isDefault": false}
            ],
            "optional": false
          }
        ]
      }
    ],
    "instructionSections": [
      {
        "name": "Make the cake",
        "steps": [
          {
            "stepNumber": 1,
            "instruction": "Preheat oven.",
            "ingredientReferences": [],
            "ingredients": [],
            "optional": false
          },
          {
            "stepNumber": 2,
            "instruction": "Mix together.",
            "ingredientReferences": [
              {"ingredientName": "all-purpose flour", "quantity": 2.0, "unit": "cups"},
              {"ingredientName": "sugar", "quantity": 1.0, "unit": "cup"}
            ],
            "optional": false,
            "ingredients": [
              {
                "name": "all-purpose flour",
                "notes": "sifted",
                "alternates": [],
                "amounts": [
                  {"value": 2.0, "unit": "cups", "type": "volume", "isDefault": true},
                  {"value": 250.0, "unit": "grams", "type": "weight", "isDefault": false}
                ],
                "optional": false
              },
              {
                "name": "sugar",
                "notes": null,
                "alternates": [],
                "amounts": [
                  {"value": 1.0, "unit": "cup", "type": "volume", "isDefault": true},
                  {"value": 200.0, "unit": "grams", "type": "weight", "isDefault": false}
                ],
                "optional": false
              }
            ]
          }
        ]
      }
    ],
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

Guidelines:
- If the recipe has distinct sections (e.g., cake and frosting), create separate ingredientSections and instructionSections
- If there's only one section, use null for the section name
- IMPORTANT: For each ingredient, provide BOTH volume and weight measurements when possible:
  * Use the "amounts" array to provide multiple measurement options
  * Mark the original recipe measurement with "isDefault": true
  * Provide approximate conversions to other measurement types (volume to weight or vice versa)
  * Use your knowledge of common ingredient densities for conversions (e.g., flour ~125g/cup, sugar ~200g/cup, butter ~227g/cup)
  * For items that are counted (eggs, onions, etc.), use "type": "count" and only include that measurement
  * For ingredients marked "to taste", "as needed", or similar non-quantifiable amounts: leave the "amounts" array EMPTY and put the phrase in the "notes" field instead (e.g., notes: "to taste")
- IMPORTANT: Always spell out units fully (use "cups" not "c", "tablespoons" not "tbsp", "teaspoons" not "tsp", "grams" not "g", "ounces" not "oz", etc.)
- Extract quantities as decimal numbers (e.g., 0.5 for 1/2, 0.25 for 1/4)
- Include notes for ingredient modifications like "room temperature", "divided", etc.
- For ingredient alternates/substitutes (indicated by "or" in the ingredient list):
  * Extract the first option as the main ingredient
  * Parse subsequent options (separated by "or") as alternates in the alternates array
  * Apply the same amounts structure to each alternate
- For ingredientReferences, include the specific quantity used in that step if mentioned
- IMPORTANT: For each instruction step, extract the ingredients used in that step:
  * Populate the "ingredients" array with only the ingredients used in that step
  * Include the same full ingredient data structure (name, amounts with volume/weight options, notes, alternates) as the top-level ingredients
  * If an ingredient is used in multiple steps, include it in each step's ingredients array
  * Rephrase the instruction text to remove quantity mentions since they'll be displayed separately from the ingredient list
  * Example: "Add 2 cups flour" becomes "Add flour", with quantities shown in the ingredients array
- IMPORTANT: Mark ingredients and steps as optional when appropriate:
  * Set "optional": true for ingredients that are clearly marked as optional in the recipe (e.g., "optional garnish", "if desired", "for garnish")
  * Set "optional": true for instruction steps that are purely decorative or enhancement steps (e.g., "Garnish with fresh herbs if desired")
  * Set "optional": false for all ingredients and steps that are essential to the recipe
- Generate relevant tags based on the recipe type, cuisine, dietary restrictions, etc.
- Keep the story brief - just the essence of any background provided
- Return null for fields that aren't present in the source
- Always include the alternates array (empty array if no alternates)
- Always include the amounts array (with at least one measurement, or empty if the ingredient is "to taste", "as needed", etc.)
- Always include the ingredients array for each step (empty array if no ingredients used in that step)
- Always include the optional field for ingredients (default to false if not specified)
- Always include the optional field for steps (default to false if not specified)
""".trimIndent()
    }
}
