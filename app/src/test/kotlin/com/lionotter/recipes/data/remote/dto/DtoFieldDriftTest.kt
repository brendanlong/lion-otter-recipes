package com.lionotter.recipes.data.remote.dto

import com.google.firebase.firestore.util.CustomClassMapper
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.DomainFixtures
import com.lionotter.recipes.testutil.propertyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the hand-written DTO mapping against drift: every domain field needs a DTO field
 * (and vice versa), and the Firestore field-name constants must be real document keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DtoFieldDriftTest {

    @Test
    fun `every Recipe field has a RecipeDto field`() {
        assertSameFields(Recipe::class.java, RecipeDto::class.java, RECIPE_RENAMES)
    }

    @Test
    fun `every MealPlanEntry field has a MealPlanDto field`() {
        assertSameFields(MealPlanEntry::class.java, MealPlanDto::class.java, emptyMap())
    }

    @Test
    fun `Firestore writes RecipeDto fields under their property names`() {
        // @DocumentId fields are not stored in the document body
        assertEquals(propertyNames(RecipeDto::class.java) - "id", firestoreKeys(DomainFixtures.fullRecipe.toDto()))
    }

    @Test
    fun `Firestore writes MealPlanDto fields under their property names`() {
        assertEquals(
            propertyNames(MealPlanDto::class.java) - "id",
            firestoreKeys(DomainFixtures.fullMealPlanEntry.toDto())
        )
    }

    @Test
    fun `RecipeFields constants are RecipeDto document keys`() {
        assertConstantsAreKeys(RecipeFields::class.java, firestoreKeys(DomainFixtures.fullRecipe.toDto()))
    }

    @Test
    fun `MealPlanFields constants are MealPlanDto document keys`() {
        assertConstantsAreKeys(MealPlanFields::class.java, firestoreKeys(DomainFixtures.fullMealPlanEntry.toDto()))
    }

    @Test
    fun `unreadable instructions are not decoded as an empty recipe`() {
        val dto = DomainFixtures.fullRecipe.toDto().copy(instructionSectionsJson = "[{not json")
        assertThrows(CorruptRecipeException::class.java) { dto.toDomain() }
    }

    private fun assertSameFields(domain: Class<*>, dto: Class<*>, renames: Map<String, String>) {
        val expected = propertyNames(domain).map { renames[it] ?: it }.toSet()
        val actual = propertyNames(dto)
        assertTrue(
            "${dto.simpleName} is missing ${expected - actual}; map new ${domain.simpleName} fields in " +
                "${dto.simpleName}, toDto() and toDomain() (or add a rename in DtoFieldDriftTest)",
            (expected - actual).isEmpty()
        )
        assertTrue(
            "${dto.simpleName} has fields with no ${domain.simpleName} counterpart: ${actual - expected}",
            (actual - expected).isEmpty()
        )
    }

    private fun firestoreKeys(dto: Any): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return (CustomClassMapper.convertToPlainJavaTypes(dto) as Map<String, Any?>).keys
    }

    private fun assertConstantsAreKeys(constants: Class<*>, keys: Set<String>) {
        val values = constants.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue("No constants found in ${constants.simpleName}", values.isNotEmpty())
        assertTrue("Not document keys: ${values - keys}", (values - keys).isEmpty())
    }

    private companion object {
        val RECIPE_RENAMES = mapOf("instructionSections" to "instructionSectionsJson")
    }
}
