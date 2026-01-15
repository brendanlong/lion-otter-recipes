package com.lionotter.recipes.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exception thrown when the AI cannot parse the recipe content.
 * Contains a human-readable error message from the AI.
 */
class RecipeParseException(message: String) : Exception(message)

@Singleton
class AnthropicService @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    suspend fun parseRecipe(
        html: String,
        apiKey: String,
        model: String = DEFAULT_MODEL
    ): Result<RecipeParseResult> {
        return try {
            val request = AnthropicRequest(
                model = model,
                maxTokens = 16000,
                system = SYSTEM_PROMPT,
                messages = listOf(
                    AnthropicMessage(
                        role = "user",
                        content = "Parse this recipe webpage and extract the structured data:\n\n$html"
                    )
                ),
                thinking = ThinkingConfig(
                    type = "enabled",
                    budgetTokens = 8000
                )
            )

            val response = httpClient.post(ANTHROPIC_API_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                setBody(json.encodeToString(AnthropicRequest.serializer(), request))
            }

            val responseBody = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val error = try {
                    json.decodeFromString(AnthropicError.serializer(), responseBody)
                } catch (e: Exception) {
                    return Result.failure(Exception("API error: ${response.status.value} - $responseBody"))
                }
                return Result.failure(Exception("API error: ${error.error.message}"))
            }

            val anthropicResponse = json.decodeFromString(AnthropicResponse.serializer(), responseBody)
            // Find the text content block (skip thinking blocks)
            val content = anthropicResponse.content
                .firstOrNull { it.type == "text" && it.text != null }?.text
                ?: return Result.failure(Exception("No text content in response"))

            // Extract JSON from the response (it might be wrapped in markdown code blocks)
            val jsonContent = extractJson(content)

            val parseResponse = json.decodeFromString(RecipeParseResponse.serializer(), jsonContent)

            if (!parseResponse.success) {
                val errorMessage = parseResponse.error ?: "Failed to parse recipe"
                return Result.failure(RecipeParseException(errorMessage))
            }

            val recipeResult = parseResponse.recipe
                ?: return Result.failure(RecipeParseException("No recipe data in response"))

            Result.success(recipeResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-opus-4-5-20251101"

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
                ]
              }
            ],
            "amounts": [
              {"value": 1.0, "unit": "teaspoons", "type": "volume", "isDefault": true},
              {"value": 6.0, "unit": "grams", "type": "weight", "isDefault": false}
            ]
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
            "ingredients": []
          },
          {
            "stepNumber": 2,
            "instruction": "Mix together.",
            "ingredientReferences": [
              {"ingredientName": "all-purpose flour", "quantity": 2.0, "unit": "cups"},
              {"ingredientName": "sugar", "quantity": 1.0, "unit": "cup"}
            ],
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
                "name": "sugar",
                "notes": null,
                "alternates": [],
                "amounts": [
                  {"value": 1.0, "unit": "cup", "type": "volume", "isDefault": true},
                  {"value": 200.0, "unit": "grams", "type": "weight", "isDefault": false}
                ]
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
- Generate relevant tags based on the recipe type, cuisine, dietary restrictions, etc.
- Keep the story brief - just the essence of any background provided
- Return null for fields that aren't present in the source
- Always include the alternates array (empty array if no alternates)
- Always include the amounts array (with at least one measurement)
- Always include the ingredients array for each step (empty array if no ingredients used in that step)
""".trimIndent()
    }
}
