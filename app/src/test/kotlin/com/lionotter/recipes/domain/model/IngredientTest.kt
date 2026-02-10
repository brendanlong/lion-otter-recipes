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

    // --- Weight unit display tests ---

    @Test
    fun `weight format uses decimals not fractions for grams`() {
        val ingredient = Ingredient(
            name = "salt",
            amount = Amount(value = 2.67, unit = "g")
        )
        // Should show "2.7 g" not "2 2/3 g"
        assertEquals("2.7 g salt", ingredient.format())
    }

    @Test
    fun `weight format uses decimals not fractions for kg`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 10.25, unit = "kg")
        )
        assertEquals("10 kg flour", ingredient.format())
    }

    @Test
    fun `quarter kg converts to 250 grams in metric`() {
        // 0.25 kg = 250 g, should show "250 g" not "1/4 kg"
        val ingredient = Ingredient(
            name = "butter",
            amount = Amount(value = 0.25, unit = "kg")
        )
        val result = ingredient.getDisplayAmount(
            preference = MeasurementPreference.DEFAULT,
            weightSystem = UnitSystem.METRIC
        )
        assertNotNull(result)
        assertEquals(250.0, result!!.value!!, 0.01)
        assertEquals("g", result.unit)
    }

    @Test
    fun `quarter kg butter formats as 250 g`() {
        val ingredient = Ingredient(
            name = "butter",
            amount = Amount(value = 0.25, unit = "kg")
        )
        assertEquals(
            "250 g butter",
            ingredient.format(weightSystem = UnitSystem.METRIC)
        )
    }

    @Test
    fun `0_14 kg sugar formats as 140 g`() {
        val ingredient = Ingredient(
            name = "sugar",
            amount = Amount(value = 0.14, unit = "kg")
        )
        assertEquals(
            "140 g sugar",
            ingredient.format(weightSystem = UnitSystem.METRIC)
        )
    }

    @Test
    fun `9_33 g vanilla formats without fractions`() {
        val ingredient = Ingredient(
            name = "vanilla extract",
            amount = Amount(value = 9.33, unit = "g")
        )
        // Should show "9 g" (rounded since >= 10 threshold is close), actually 9.33 < 10 so 9.3
        assertEquals("9.3 g vanilla extract", ingredient.format())
    }

    @Test
    fun `weight amount under 1g shows mg`() {
        // 0.5 g = 500 mg when converting
        val ingredient = Ingredient(
            name = "saffron",
            amount = Amount(value = 500.0, unit = "mg")
        )
        assertEquals("500 mg saffron", ingredient.format())
    }

    @Test
    fun `convertToSystem converts small kg to grams`() {
        val result = convertToSystem(0.25, "kg", weightSystem = UnitSystem.METRIC)
        assertEquals(250.0, result.value!!, 0.01)
        assertEquals("g", result.unit)
    }

    @Test
    fun `convertToSystem keeps grams for moderate amounts`() {
        val result = convertToSystem(500.0, "g", weightSystem = UnitSystem.METRIC)
        assertEquals(500.0, result.value!!, 0.01)
        assertEquals("g", result.unit)
    }

    @Test
    fun `convertToSystem converts large grams to kg`() {
        val result = convertToSystem(15000.0, "g", weightSystem = UnitSystem.METRIC)
        assertEquals(15.0, result.value!!, 0.01)
        assertEquals("kg", result.unit)
    }

    @Test
    fun `convertToSystem converts sub-gram to mg`() {
        val result = convertToSystem(0.5, "g", weightSystem = UnitSystem.METRIC)
        assertEquals(500.0, result.value!!, 0.01)
        assertEquals("mg", result.unit)
    }

    @Test
    fun `weight preference converts cups flour to grams`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        val result = ingredient.getDisplayAmount(
            preference = MeasurementPreference.WEIGHT,
            weightSystem = UnitSystem.METRIC
        )
        assertNotNull(result)
        // 2 cups = 2 * 236.588 mL * 0.51 density = 241.32 g
        assertEquals("g", result!!.unit)
        assertTrue(result.value!! > 200 && result.value!! < 300)
    }

    @Test
    fun `weight preference converts cups flour to oz when customary`() {
        val ingredient = Ingredient(
            name = "flour",
            amount = Amount(value = 2.0, unit = "cup"),
            density = 0.51
        )
        val result = ingredient.getDisplayAmount(
            preference = MeasurementPreference.WEIGHT,
            weightSystem = UnitSystem.CUSTOMARY
        )
        assertNotNull(result)
        assertEquals("oz", result!!.unit)
    }

    @Test
    fun `customary weight uses oz for small amounts`() {
        // 100g should be about 3.5 oz
        val ingredient = Ingredient(
            name = "cheese",
            amount = Amount(value = 100.0, unit = "g")
        )
        val result = ingredient.getDisplayAmount(
            preference = MeasurementPreference.DEFAULT,
            weightSystem = UnitSystem.CUSTOMARY
        )
        assertNotNull(result)
        assertEquals("oz", result!!.unit)
        assertEquals(3.53, result.value!!, 0.01)
    }

    @Test
    fun `customary weight returns oz for large amounts`() {
        // 1000g should be about 35.27 oz - the format layer handles lb+oz display
        val ingredient = Ingredient(
            name = "chicken",
            amount = Amount(value = 1000.0, unit = "g")
        )
        val result = ingredient.getDisplayAmount(
            preference = MeasurementPreference.DEFAULT,
            weightSystem = UnitSystem.CUSTOMARY
        )
        assertNotNull(result)
        assertEquals("oz", result!!.unit)
        assertEquals(35.27, result.value!!, 0.01)
    }

    @Test
    fun `whole gram values display without decimals`() {
        val ingredient = Ingredient(
            name = "sugar",
            amount = Amount(value = 100.0, unit = "g")
        )
        assertEquals("100 g sugar", ingredient.format())
    }

    @Test
    fun `gram values between 1 and 10 show one decimal`() {
        val ingredient = Ingredient(
            name = "salt",
            amount = Amount(value = 2.5, unit = "g")
        )
        assertEquals("2.5 g salt", ingredient.format())
    }

    @Test
    fun `gram values 10 and above round to whole number`() {
        val ingredient = Ingredient(
            name = "sugar",
            amount = Amount(value = 15.7, unit = "g")
        )
        assertEquals("16 g sugar", ingredient.format())
    }

    @Test
    fun `volume units still use fractions`() {
        // Ensure volume formatting is unchanged
        val ingredient = Ingredient(
            name = "milk",
            amount = Amount(value = 0.25, unit = "cup")
        )
        assertEquals("1/4 cup milk", ingredient.format())
    }

    // --- Compound lb+oz display tests ---

    @Test
    fun `customary weight formats as lb oz for amounts over 1 lb`() {
        // 1000g = ~35.27 oz = 2 lbs 3.3 oz
        val ingredient = Ingredient(
            name = "chicken",
            amount = Amount(value = 1000.0, unit = "g")
        )
        val formatted = ingredient.format(weightSystem = UnitSystem.CUSTOMARY)
        assertTrue("Expected lb+oz format, got: $formatted", formatted.contains("lb"))
        assertTrue("Expected lb+oz format, got: $formatted", formatted.contains("oz"))
        assertEquals("2 lb 3.3 oz chicken", formatted)
    }

    @Test
    fun `customary weight shows exact lbs without oz when even`() {
        // 32 oz = exactly 2 lbs
        val ingredient = Ingredient(
            name = "beef",
            amount = Amount(value = 32.0, unit = "oz")
        )
        assertEquals("2 lb beef", ingredient.format(weightSystem = UnitSystem.CUSTOMARY))
    }

    @Test
    fun `customary weight shows 1 lb singular`() {
        // 16 oz = exactly 1 lb
        val ingredient = Ingredient(
            name = "butter",
            amount = Amount(value = 16.0, unit = "oz")
        )
        assertEquals("1 lb butter", ingredient.format(weightSystem = UnitSystem.CUSTOMARY))
    }

    @Test
    fun `customary weight shows lb and oz for 1 lb 4 oz`() {
        val ingredient = Ingredient(
            name = "butter",
            amount = Amount(value = 20.0, unit = "oz")
        )
        assertEquals("1 lb 4 oz butter", ingredient.format(weightSystem = UnitSystem.CUSTOMARY))
    }

    @Test
    fun `customary weight under 16 oz shows just oz`() {
        val ingredient = Ingredient(
            name = "cheese",
            amount = Amount(value = 8.0, unit = "oz")
        )
        assertEquals("8 oz cheese", ingredient.format(weightSystem = UnitSystem.CUSTOMARY))
    }
}
