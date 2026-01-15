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
                maxTokens = 4096,
                system = SYSTEM_PROMPT,
                messages = listOf(
                    AnthropicMessage(
                        role = "user",
                        content = "Parse this recipe webpage and extract the structured data:\n\n$html"
                    )
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
            val content = anthropicResponse.content.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(Exception("No text content in response"))

            // Extract JSON from the response (it might be wrapped in markdown code blocks)
            val jsonContent = extractJson(content)

            val recipeResult = json.decodeFromString(RecipeParseResult.serializer(), jsonContent)
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

Return ONLY valid JSON (no markdown, no explanations) with this exact structure:
{
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
          "quantity": 2.0,
          "unit": "cups",
          "notes": "sifted"
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
          "instruction": "Preheat oven to 350°F.",
          "ingredientReferences": []
        },
        {
          "stepNumber": 2,
          "instruction": "Mix the flour with sugar.",
          "ingredientReferences": [
            {"ingredientName": "all-purpose flour", "quantity": 2.0, "unit": "cups"},
            {"ingredientName": "sugar", "quantity": 1.0, "unit": "cup"}
          ]
        }
      ]
    }
  ],
  "tags": ["dessert", "cake", "baking"],
  "imageUrl": "https://example.com/image.jpg"
}

Guidelines:
- If the recipe has distinct sections (e.g., cake and frosting), create separate ingredientSections and instructionSections
- If there's only one section, use null for the section name
- Extract quantities as decimal numbers (e.g., 0.5 for 1/2, 0.25 for 1/4)
- Include notes for ingredient modifications like "room temperature", "divided", etc.
- For ingredientReferences, include the specific quantity used in that step if mentioned
- Generate relevant tags based on the recipe type, cuisine, dietary restrictions, etc.
- Extract the main image URL if available
- Keep the story brief - just the essence of any background provided
- Return null for fields that aren't present in the source
""".trimIndent()
    }
}
