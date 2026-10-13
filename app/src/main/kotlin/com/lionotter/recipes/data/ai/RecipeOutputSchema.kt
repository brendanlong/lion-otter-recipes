package com.lionotter.recipes.data.ai

/** The JSON schema for the structured output of recipe parsing. */
object RecipeOutputSchema {
    private fun obj(properties: Map<String, Any>, required: List<String>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required,
        "additionalProperties" to false
    )

    private val STRING = mapOf("type" to "string")
    private val NUMBER = mapOf("type" to "number")
    private val INTEGER = mapOf("type" to "integer")
    private val BOOLEAN = mapOf("type" to "boolean")
    private fun arrayOf(items: Map<String, Any>) = mapOf("type" to "array", "items" to items)
    private fun nullable(primitive: Map<String, Any>) = mapOf("type" to listOf(primitive["type"], "null"))
    private fun nullableObject(objectSchema: Map<String, Any>) =
        mapOf("anyOf" to listOf(objectSchema, mapOf("type" to "null")))

    /**
     * JSON schema for [RecipeParseResponse].
     *
     * The API compiles this into a grammar with an undocumented size limit, and optional
     * properties nested inside arrays grow that grammar fastest (with alternates as optional
     * ingredient objects, every request failed with "Schema is too complex"). So every property
     * is required, with absent values expressed as null. Nullable properties count toward the
     * API's limit of 16 union-typed parameters (inline copies such as amount count once per
     * use); this schema uses 14, so adding another nullable field needs care.
     * Alternates are a non-recursive ingredient subset.
     * On failure the model still returns a placeholder recipe, which is ignored.
     */
    val RECIPE_RESPONSE_SCHEMA: Map<String, Any> by lazy {
        val amount = obj(mapOf("value" to NUMBER, "unit" to nullable(STRING)), listOf("value", "unit"))
        val alternate = obj(
            mapOf(
                "name" to STRING,
                "amount" to nullableObject(amount),
                "density" to nullable(NUMBER),
                "notes" to nullable(STRING)
            ),
            listOf("name", "amount", "density", "notes")
        )
        val ingredient = obj(
            mapOf(
                "name" to STRING,
                "amount" to nullableObject(amount),
                "density" to nullable(NUMBER),
                "notes" to nullable(STRING),
                "optional" to BOOLEAN,
                "alternates" to arrayOf(alternate)
            ),
            listOf("name", "amount", "density", "notes", "optional", "alternates")
        )
        val step = obj(
            mapOf(
                "stepNumber" to INTEGER,
                "instruction" to STRING,
                "ingredients" to arrayOf(ingredient),
                "yields" to INTEGER,
                "optional" to BOOLEAN
            ),
            listOf("stepNumber", "instruction", "ingredients", "yields", "optional")
        )
        val section = obj(
            mapOf("name" to nullable(STRING), "steps" to arrayOf(step)),
            listOf("name", "steps")
        )
        val recipe = obj(
            mapOf(
                "name" to STRING,
                "story" to nullable(STRING),
                "servings" to nullable(INTEGER),
                "prepTime" to nullable(STRING),
                "cookTime" to nullable(STRING),
                "totalTime" to nullable(STRING),
                "instructionSections" to arrayOf(section),
                "equipment" to arrayOf(STRING),
                "tags" to arrayOf(STRING)
            ),
            listOf(
                "name", "story", "servings", "prepTime", "cookTime", "totalTime",
                "instructionSections", "equipment", "tags"
            )
        )
        obj(
            mapOf("success" to BOOLEAN, "error" to STRING, "recipe" to recipe),
            listOf("success", "error", "recipe")
        )
    }
}
