package com.lionotter.recipes.testutil

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Keeps [DomainFixtures] complete: every field of every domain model reachable from the
 * fixtures must be set to a non-default value somewhere, so the DTO round-trip tests notice
 * when a newly added field is dropped. If this fails after adding a field, set it in the fixture.
 */
class DomainFixturesTest {

    @Test
    fun `recipe fixture sets every field`() = assertEveryFieldSet(DomainFixtures.fullRecipe)

    @Test
    fun `meal plan fixture sets every field`() = assertEveryFieldSet(DomainFixtures.fullMealPlanEntry)

    private fun assertEveryFieldSet(root: Any) {
        val instances = linkedMapOf<Class<*>, MutableList<Any>>()
        fun collect(value: Any?) {
            when {
                value == null -> Unit
                value is Collection<*> -> value.forEach(::collect)
                value.javaClass.name.startsWith(DOMAIN_MODEL_PACKAGE) && !value.javaClass.isEnum -> {
                    instances.getOrPut(value.javaClass) { mutableListOf() }.add(value)
                    propertyFields(value.javaClass).forEach { collect(it.get(value)) }
                }
            }
        }
        collect(root)

        val unset = instances.flatMap { (type, objects) ->
            propertyFields(type)
                .filter { field -> objects.none { isSet(field.get(it)) } }
                .map { "${type.simpleName}.${it.name}" }
        }
        assertTrue("Set these fields to non-default values in DomainFixtures: $unset", unset.isEmpty())
    }

    /** Null, empty, false, 0, 1 and the epoch count as "probably a default". */
    private fun isSet(value: Any?): Boolean = when (value) {
        null, false -> false
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0 && value.toDouble() != 1.0
        is Instant -> value != Instant.fromEpochMilliseconds(0)
        else -> true
    }

    private companion object {
        const val DOMAIN_MODEL_PACKAGE = "com.lionotter.recipes.domain.model."
    }
}
