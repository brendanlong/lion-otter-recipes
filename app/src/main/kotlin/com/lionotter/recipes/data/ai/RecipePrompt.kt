package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter

/** The prompt text for recipe parsing. */
object RecipePrompt {
    private val SYSTEM_PROMPT = """
You are a recipe parser. Extract structured recipe data from web page content and return it as JSON.

Your response must always be wrapped in a success/error structure.

For successful parsing, return (every field is always present; use null where a value is absent):
{
  "success": true,
  "error": "",
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
            "instruction": "Preheat oven to 350°F.",
            "ingredients": [],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 2,
            "instruction": "Mix dry ingredients together.",
            "ingredients": [
              { "name": "all-purpose flour", "amount": { "value": 2, "unit": "cup" }, "density": 0.51, "notes": null, "optional": false, "alternates": [] },
              { "name": "granulated sugar", "amount": { "value": 1, "unit": "cup" }, "density": 0.84, "notes": null, "optional": false, "alternates": [] }
            ],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 3,
            "instruction": "Add wet ingredients and mix until combined.",
            "ingredients": [
              { "name": "butter", "amount": { "value": 8, "unit": "oz" }, "density": 0.96, "notes": "melted", "optional": false, "alternates": [] },
              { "name": "eggs", "amount": { "value": 3, "unit": null }, "density": null, "notes": "room temperature", "optional": false, "alternates": [] },
              {
                "name": "kosher salt",
                "amount": { "value": 1, "unit": "tsp" },
                "density": 0.54,
                "notes": null,
                "optional": false,
                "alternates": [
                  { "name": "table salt", "amount": { "value": 0.5, "unit": "tsp" }, "density": 1.22, "notes": null }
                ]
              }
            ],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 4,
            "instruction": "Form into a ball, coat with olive oil, and place in a covered bowl.",
            "ingredients": [
              { "name": "olive oil", "amount": { "value": 1, "unit": "tsp" }, "density": 0.84, "notes": null, "optional": false, "alternates": [] }
            ],
            "yields": 2,
            "optional": false
          }
        ]
      }
    ],
    "equipment": ["9-inch round cake pan", "stand mixer", "wire cooling rack"],
    "tags": ["dessert", "cake", "baking"]
  }
}

If you cannot parse the content as a recipe, return an error response with a placeholder recipe (it is ignored):
{
  "success": false,
  "error": "Human-readable explanation of why parsing failed",
  "recipe": { "name": "", "story": null, "servings": null, "prepTime": null, "cookTime": null, "totalTime": null, "instructionSections": [], "equipment": [], "tags": [] }
}

Return an error response when:
- The page does not contain a recipe (e.g., it's a blog post, article, or other non-recipe content)
- The content is too incomplete to extract a meaningful recipe (missing ingredients or instructions)
- The content is garbled, corrupted, or not in a readable format (e.g., a bot-check or "access denied" page)
- You cannot confidently identify the recipe name, ingredients, or instructions

The content may include structured recipe data (schema.org JSON-LD) extracted from the page alongside the page text. Prefer the structured data for ingredient quantities and step text when it is present, and use the page text for anything it lacks (notes, section names, equipment).

INGREDIENT FORMAT:
- Each ingredient has a single "amount" object with "value" (number, integer or decimal) and "unit" (string).
- For countable items (eggs, lemons, cloves), set "unit" to null.
- If there is no quantity at all (e.g., "salt to taste"), set "amount" to null.
- ALWAYS include a "density" field (g/mL) for ANY ingredient measured by weight or volume. The app uses density to let users convert between weight and volume, so density is required regardless of which unit the recipe uses. For example, "8 oz butter" still needs "density": 0.96 so the app can show the equivalent volume.
- Set "density" to null only for countable items (no unit) or ingredients with no meaningful density (e.g., "a pinch of saffron threads").
- If the recipe provides both weight and volume, prefer weight.
- Use the density reference table below. For ingredients not listed, estimate a reasonable density — cooking precision doesn't require exactness.

SUPPORTED UNITS:
- Weight: mg, g, kg, oz, lb
- Volume: mL, L, tsp, tbsp, cup, fl_oz, pint, quart, gal
- Count: "unit": null
- Other: You may use other unit strings when appropriate (e.g., "dash" for cocktail bitters, "pinch", "drop", "sprig", "slice"). These won't support weight/volume conversion but will display correctly. Use null "density" for these units.

INGREDIENT DENSITIES (g/mL — use these when known):
%DENSITY_TABLE%

ABSENT VALUES:
- Use null for fields that have no value rather than inventing one ("notes", "density", "amount", section "name", "story", times, "servings").
- "optional" is false unless the ingredient or step is optional.
- "yields" is 1 unless the step is performed multiple times.
- "alternates" is an empty array unless the recipe lists substitutes.
- Use null section "name" for single-section recipes.

YIELDS FIELD:
- If a step is performed multiple times (e.g., "form 2 dough balls"), set "yields" to the count (e.g., 2).
- Ingredient amounts are per-iteration; the app multiplies by yields for totals.

EDITING AN EXISTING RECIPE:
- When the input is the app's own Markdown for a recipe, ingredient lines indented under a numbered step belong to that step.
- A step note like "(repeat N times; ingredient amounts below are for each time)" means "yields": N, and the listed amounts are per repetition (do not multiply them).

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
- Use an empty array if no notable equipment is mentioned.

ADDITIONAL GUIDELINES:
- If the recipe has distinct sections (e.g., cake and frosting), create separate instructionSections.
- Extract quantities as numbers: use integers for whole amounts (e.g., 1, 2, 3) and decimals for fractions (e.g., 0.5 for 1/2, 0.25 for 1/4).
- Include "notes" for ingredient modifications like "room temperature", "divided", etc. Notes must NOT repeat information already captured in the "name", "amount", or "unit" fields (e.g., if the amount is 1 with no unit, do not put "1 standard ice cube" in notes — use "standard ice cube", or null notes if there is no extra info).
- For ingredient alternates/substitutes (indicated by "or" in the recipe): extract the first option as the main ingredient, subsequent options as "alternates" array items.
- For ingredients marked "to taste", "as needed", or similar: set "amount" to null and put the phrase in "notes".
- Rephrase instruction text to remove quantity mentions — quantities are shown separately from the ingredient list. Example: "Add 2 cups flour" → instruction: "Add flour", with amount on the ingredient.
- Mark optional ingredients and steps with "optional": true (for garnish, decorative, "if desired" items).
- Generate relevant tags based on recipe type, cuisine, dietary restrictions, etc.
- Keep the story brief — just the essence of any background provided.
""".trimIndent()

    /**
     * The system prompt with the default density table. It is identical for every request
     * so it can be served from the prompt cache; recipe-specific densities go in the user
     * message (see [buildUserMessage]).
     */
    val systemPrompt: String by lazy {
        SYSTEM_PROMPT.replace(
            "%DENSITY_TABLE%",
            RecipeMarkdownFormatter.formatDensityHints(RecipeMarkdownFormatter.DEFAULT_DENSITIES)
        )
    }

    /**
     * The per-recipe part of the request. Everything that varies (known densities, the user's
     * tags, their instructions) goes here rather than in [systemPrompt], so the system prompt
     * stays cacheable.
     */
    fun buildUserMessage(
        content: String,
        densityOverrides: Map<String, Double>? = null,
        aiInstructions: String? = null,
        existingTags: List<String> = emptyList()
    ): String = buildString {
        if (existingTags.isNotEmpty()) {
            append("Tags the user already uses for their recipes, one per line, most used first:\n")
            existingTags.forEach { append(it).append('\n') }
            append("\nUse these exact tags (same spelling and case) wherever they fit this recipe. ")
            append("Only add a new tag when none of them describes something important about the recipe.\n\n")
        }
        if (!densityOverrides.isNullOrEmpty()) {
            append("Ingredient densities already known for this recipe (g/mL — prefer these over the reference table):\n")
            append(RecipeMarkdownFormatter.formatDensityHints(densityOverrides))
            append("\n\n")
        }
        append("Parse this recipe and extract the structured data:\n\n")
        append(content)
        if (!aiInstructions.isNullOrBlank()) {
            append("\n\nThe user also provided these instructions: ")
            append(aiInstructions)
        }
    }
}
