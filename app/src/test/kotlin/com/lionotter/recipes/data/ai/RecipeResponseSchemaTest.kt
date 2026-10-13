package com.lionotter.recipes.data.ai

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structured-output schema in [RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA] is written by hand.
 * Check it against the kotlinx serializers that decode the response: every schema property must
 * exist with a compatible type, and every property without a default must be required. Optional
 * serializer properties may be left out of the schema (e.g. the non-recursive alternate shape).
 */
class RecipeResponseSchemaTest {

    @Test
    fun `schema matches the response serializers`() {
        val problems = mutableListOf<String>()
        check(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA, RecipeParseResponse.serializer().descriptor, "$", problems)
        assertTrue("RECIPE_RESPONSE_SCHEMA is out of sync:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * The API rejects schemas whose compiled grammar is too large, without saying exactly where
     * the limit is. Optional properties grow it fastest, so require every property, and keep the
     * union-typed (nullable) parameters well under the documented maximum of 16.
     */
    @Test
    fun `schema stays within structured output complexity limits`() {
        val optional = mutableListOf<String>()
        var unions = 0
        fun walk(schema: Map<String, Any>, path: String) {
            if (schema.containsKey("anyOf") || schema["type"] is List<*>) unions++
            @Suppress("UNCHECKED_CAST")
            (schema["anyOf"] as? List<Map<String, Any>>)?.forEach { walk(it, path) }
            @Suppress("UNCHECKED_CAST")
            (schema["properties"] as? Map<String, Map<String, Any>>)?.let { properties ->
                @Suppress("UNCHECKED_CAST")
                val required = (schema["required"] as List<String>).toSet()
                properties.forEach { (name, child) ->
                    if (name !in required) optional += "$path.$name"
                    walk(child, "$path.$name")
                }
            }
            @Suppress("UNCHECKED_CAST")
            (schema["items"] as? Map<String, Any>)?.let { walk(it, "$path[]") }
        }
        walk(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA, "$")
        assertTrue("Optional properties make the schema too complex to compile: $optional", optional.isEmpty())
        assertTrue("$unions union-typed parameters; the API allows 16, keep a margin", unions <= MAX_UNIONS)
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(schema: Map<String, Any>, descriptor: SerialDescriptor, path: String, problems: MutableList<String>) {
        // Nullable forms: {"anyOf": [schema, {"type": "null"}]} and {"type": [primitive, "null"]}
        (schema["anyOf"] as? List<Map<String, Any>>)?.let { options ->
            if (!descriptor.isNullable) problems += "$path: schema allows null but ${descriptor.serialName} is not nullable"
            options.filter { it["type"] != "null" }.forEach { check(it, descriptor, path, problems) }
            return
        }
        (schema["type"] as? List<String>)?.let { types ->
            if ("null" in types && !descriptor.isNullable) {
                problems += "$path: schema allows null but ${descriptor.serialName} is not nullable"
            }
            check(schema + ("type" to types.single { it != "null" }), descriptor, path, problems)
            return
        }
        when (val type = schema["type"]) {
            "object" -> {
                if (descriptor.kind != StructureKind.CLASS) {
                    problems += "$path: schema object but serializer kind is ${descriptor.kind}"
                    return
                }
                val properties = schema["properties"] as Map<String, Map<String, Any>>
                val required = (schema["required"] as List<String>).toSet()
                val elementNames = (0 until descriptor.elementsCount).map(descriptor::getElementName)

                for ((name, propertySchema) in properties) {
                    val index = descriptor.getElementIndex(name)
                    if (index < 0) {
                        problems += "$path.$name: not a property of ${descriptor.serialName} ($elementNames)"
                    } else {
                        check(propertySchema, descriptor.getElementDescriptor(index), "$path.$name", problems)
                    }
                }
                (required - properties.keys).forEach { problems += "$path.$it: required but not in properties" }
                for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    if (!descriptor.isElementOptional(index) && name !in required) {
                        problems += "$path.$name: has no default in ${descriptor.serialName} so the schema must require it"
                    }
                }
            }
            "array" -> {
                if (descriptor.kind != StructureKind.LIST) {
                    problems += "$path: schema array but serializer kind is ${descriptor.kind}"
                    return
                }
                check(schema["items"] as Map<String, Any>, descriptor.getElementDescriptor(0), "$path[]", problems)
            }
            else -> {
                val expected: Set<SerialKind> = when (type) {
                    "string" -> setOf(PrimitiveKind.STRING)
                    "number" -> setOf(PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT)
                    "integer" -> setOf(PrimitiveKind.INT, PrimitiveKind.LONG)
                    "boolean" -> setOf(PrimitiveKind.BOOLEAN)
                    else -> emptySet()
                }
                if (descriptor.kind !in expected) {
                    problems += "$path: schema type $type but serializer kind is ${descriptor.kind}"
                }
            }
        }
    }

    private companion object {
        const val MAX_UNIONS = 14
    }
}
