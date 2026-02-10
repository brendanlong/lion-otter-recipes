package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientTest {

    @Test
    fun `format with all fields`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51,
            notes = "sifted"
        )
        assertEquals("2 cups flour, sifted", ingredient.format())
    }

    @Test
    fun `format without notes`() {
        val ingredient = Ingredient(
            name = "sugar",
            amount = Amount(value = 1.0, unit = "cup"),
            density = 0.84
        )
        assertEquals("1 cup sugar", ingredient.format())
    }

    @Test
    fun `format count ingredient without unit`() {
        val ingredient = Ingredient(
            name = "eggs",
            amount = Amount(value = 3.0)
        )
        assertEquals("3 eggs", ingredient.format())
    }

    @Test
    fun `format without amount`() {
        val ingredient = Ingredient(
            name = "salt",
            notes = "to taste"
        )
        assertEquals("salt, to taste", ingredient.format())
    }

    @Test
    fun `format with null value in amount`() {
        val ingredient = Ingredient(
            name = "salt",
            amount = Amount(value = null, unit = "tsp"),
            notes = "to taste"
        )
        assertEquals("salt, to taste", ingredient.format())
    }

    @Test
    fun `format oz unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "granulated sugar",
            amount = Amount(value = 5.0, unit = "oz"),
            density = 0.84
        )
        assertEquals("5 oz granulated sugar", ingredient.format(
            weightSystem = UnitSystem.CUSTOMARY
        ))
    }

    @Test
    fun `format mL unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "water",
            amount = Amount(value = 250.0, unit = "mL"),
            density = 1.0
        )
        assertEquals("250 mL water", ingredient.format(
            volumeSystem = UnitSystem.METRIC
        ))
    }

    @Test
    fun `format g unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 500.0, unit = "g"),
            density = 0.51
        )
        assertEquals("500 g flour", ingredient.format())
    }

    @Test
    fun `format lb unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "chicken",
            amount = Amount(value = 3.0, unit = "lb"),
            density = 1.0
        )
        assertEquals("3 lb chicken", ingredient.format(
            weightSystem = UnitSystem.CUSTOMARY
        ))
    }

    @Test
    fun `format tsp unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "salt",
            amount = Amount(value = 2.0, unit = "tsp"),
            density = 1.22
        )
        assertEquals("2 tsp salt", ingredient.format())
    }

    @Test
    fun `format tbsp unit does not pluralize`() {
        val ingredient = Ingredient(
            name = "olive oil",
            amount = Amount(value = 3.0, unit = "tbsp"),
            density = 0.92
        )
        assertEquals("3 tbsp olive oil", ingredient.format())
    }

    @Test
    fun `format with scaling`() {
        val ingredient = Ingredient(
            name = "butter",
            amount = Amount(value = 1.0, unit = "cup"),
            density = 0.96
        )
        assertEquals("2 cups butter", ingredient.format(scale = 2.0))
    }

    @Test
    fun `format half quantity`() {
        val ingredient = Ingredient(
            name = "milk",
            amount = Amount(value = 0.5, unit = "cup"),
            density = 0.96
        )
        assertEquals("1/2 cup milk", ingredient.format())
    }

    @Test
    fun `format quarter quantity`() {
        val ingredient = Ingredient(
            name = "vanilla",
            amount = Amount(value = 0.25, unit = "tsp"),
            density = 0.95
        )
        assertEquals("1/4 tsp vanilla", ingredient.format())
    }

    @Test
    fun `format mixed number`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.5, unit = "cup"),
            density = 0.51
        )
        assertEquals("2 1/2 cups flour", ingredient.format())
    }

    @Test
    fun `format third quantity`() {
        val ingredient = Ingredient(
            name = "oil",
            amount = Amount(value = 0.33, unit = "cup"),
            density = 0.84
        )
        assertEquals("1/3 cup oil", ingredient.format())
    }

    @Test
    fun `format two thirds quantity`() {
        val ingredient = Ingredient(
            name = "water",
            amount = Amount(value = 0.66, unit = "cup"),
            density = 0.96
        )
        assertEquals("2/3 cup water", ingredient.format())
    }

    @Test
    fun `format three quarters quantity`() {
        val ingredient = Ingredient(
            name = "cream",
            amount = Amount(value = 0.75, unit = "cup"),
            density = 0.96
        )
        assertEquals("3/4 cup cream", ingredient.format())
    }

    @Test
    fun `format scaling with fractions`() {
        val ingredient = Ingredient(
            name = "sugar",
            amount = Amount(value = 1.0, unit = "cup"),
            density = 0.84
        )
        assertEquals("1/2 cup sugar", ingredient.format(scale = 0.5))
    }

    @Test
    fun `format with alternates`() {
        val alternate = Ingredient(
            name = "table salt",
            amount = Amount(value = 0.5, unit = "tsp"),
            density = 1.22
        )
        val ingredient = Ingredient(
            name = "kosher salt",
            amount = Amount(value = 1.0, unit = "tsp"),
            density = 0.54,
            alternates = listOf(alternate)
        )
        assertEquals("1 tsp kosher salt", ingredient.format())
    }

    @Test
    fun `alternate formats correctly with scaling`() {
        val alternate = Ingredient(
            name = "table salt",
            amount = Amount(value = 0.5, unit = "tsp"),
            density = 1.22
        )
        assertEquals("1 tsp table salt", alternate.format(scale = 2.0))
    }

    @Test
    fun `ingredient with multiple alternates`() {
        val alternates = listOf(
            Ingredient(
                name = "table salt",
                amount = Amount(value = 0.5, unit = "tsp"),
                density = 1.22
            ),
            Ingredient(
                name = "sea salt",
                amount = Amount(value = 0.75, unit = "tsp")
            )
        )
        val ingredient = Ingredient(
            name = "kosher salt",
            amount = Amount(value = 1.0, unit = "tsp"),
            density = 0.54,
            alternates = alternates
        )
        assertEquals("1 tsp kosher salt", ingredient.format())
        assertEquals(2, ingredient.alternates.size)
    }

    @Test
    fun `format with volume preference converts weight to volume`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 250.0, unit = "g"),
            density = 0.51
        )
        val formatted = ingredient.format(preference = MeasurementPreference.VOLUME)
        assertTrue(formatted.contains("flour"))
        assertTrue(formatted.contains("cup"))
    }

    @Test
    fun `format with weight preference converts volume to weight`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        val formatted = ingredient.format(preference = MeasurementPreference.WEIGHT)
        assertTrue(formatted.contains("flour"))
        assertTrue(formatted.contains("g") || formatted.contains("oz"))
    }

    @Test
    fun `format with default preference returns original amount`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        assertEquals("2 cups flour", ingredient.format(preference = MeasurementPreference.DEFAULT))
    }

    @Test
    fun `format falls back to original when no density`() {
        val ingredient = Ingredient(
            name = "eggs",
            amount = Amount(value = 3.0)
        )
        assertEquals("3 eggs", ingredient.format(preference = MeasurementPreference.VOLUME))
        assertEquals("3 eggs", ingredient.format(preference = MeasurementPreference.WEIGHT))
    }

    @Test
    fun `supportsConversion returns true when density and unit present`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        assertTrue(ingredient.supportsConversion())
    }

    @Test
    fun `supportsConversion returns false when no density`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup")
        )
        assertFalse(ingredient.supportsConversion())
    }

    @Test
    fun `supportsConversion returns false when no unit (count item)`() {
        val ingredient = Ingredient(
            name = "eggs",
            amount = Amount(value = 3.0),
            density = 1.0
        )
        assertFalse(ingredient.supportsConversion())
    }

    @Test
    fun `supportsConversion returns false when no amount`() {
        val ingredient = Ingredient(
            name = "salt",
            density = 1.22
        )
        assertFalse(ingredient.supportsConversion())
    }

    @Test
    fun `getDisplayAmount returns scaled amount for default preference`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        val result = ingredient.getDisplayAmount(scale = 2.0, preference = MeasurementPreference.DEFAULT)
        assertNotNull(result)
        assertEquals(4.0, result!!.value!!, 0.01)
        assertEquals("cup", result.unit)
    }

    @Test
    fun `getDisplayAmount returns null when no amount`() {
        val ingredient = Ingredient(name = "salt")
        assertNull(ingredient.getDisplayAmount())
    }

    @Test
    fun `weight units are recognized`() {
        val weightUnits = listOf("mg", "g", "kg", "oz", "lb")
        for (unit in weightUnits) {
            assertEquals("Unit $unit should be WEIGHT", UnitCategory.WEIGHT, unitType(unit))
        }
    }

    @Test
    fun `volume units are recognized`() {
        val volumeUnits = listOf("mL", "L", "tsp", "tbsp", "cup", "fl_oz", "pint", "quart", "gal")
        for (unit in volumeUnits) {
            assertEquals("Unit $unit should be VOLUME", UnitCategory.VOLUME, unitType(unit))
        }
    }

    @Test
    fun `unknown units return null type`() {
        assertNull(unitType("large"))
        assertNull(unitType("pinch"))
        assertNull(unitType("bunch"))
    }
}
