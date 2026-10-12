package com.lionotter.recipes.data.remote

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structured-output schema in [AnthropicService.RECIPE_RESPONSE_SCHEMA] is written by hand.
 * Check it against the kotlinx serializers that decode the response: every schema property must
 * exist with a compatible type, and every property without a default must be required. Optional
 * serializer properties may be left out of the schema (e.g. the non-recursive alternate shape).
 */
class RecipeResponseSchemaTest {

    @Test
    fun `schema matches the response serializers`() {
        val problems = mutableListOf<String>()
        check(AnthropicService.RECIPE_RESPONSE_SCHEMA, RecipeParseResponse.serializer().descriptor, "$", problems)
        assertTrue("RECIPE_RESPONSE_SCHEMA is out of sync:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(schema: Map<String, Any>, descriptor: SerialDescriptor, path: String, problems: MutableList<String>) {
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
}
