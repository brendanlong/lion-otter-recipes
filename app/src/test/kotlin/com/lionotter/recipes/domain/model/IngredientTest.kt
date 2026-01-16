package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientTest {

    private fun volumeMeasurement(value: Double, unit: String, isDefault: Boolean = true) =
        Measurement(value = value, unit = unit, type = MeasurementType.VOLUME, isDefault = isDefault)

    private fun weightMeasurement(value: Double, unit: String, isDefault: Boolean = false) =
        Measurement(value = value, unit = unit, type = MeasurementType.WEIGHT, isDefault = isDefault)

    private fun countMeasurement(value: Double, unit: String, isDefault: Boolean = true) =
        Measurement(value = value, unit = unit, type = MeasurementType.COUNT, isDefault = isDefault)

    @Test
    fun `format with all fields`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(volumeMeasurement(2.0, "cups")),
            notes = "sifted"
        )
        assertEquals("2 cups flour, sifted", ingredient.format())
    }

    @Test
    fun `format without notes`() {
        val ingredient = Ingredient(
            name = "sugar",
            amounts = listOf(volumeMeasurement(1.0, "cup"))
        )
        assertEquals("1 cup sugar", ingredient.format())
    }

    @Test
    fun `format with count measurement`() {
        val ingredient = Ingredient(
            name = "eggs",
            amounts = listOf(countMeasurement(3.0, "large"))
        )
        assertEquals("3 larges eggs", ingredient.format())
    }

    @Test
    fun `format without amounts`() {
        val ingredient = Ingredient(
            name = "salt",
            notes = "to taste"
        )
        assertEquals("salt, to taste", ingredient.format())
    }

    @Test
    fun `format with null value in measurement (to taste)`() {
        val ingredient = Ingredient(
            name = "salt",
            amounts = listOf(
                Measurement(value = null, unit = "to taste", type = MeasurementType.VOLUME, isDefault = true)
            ),
            notes = "to taste"
        )
        assertEquals("salt, to taste", ingredient.format())
    }

    @Test
    fun `format with scaling`() {
        val ingredient = Ingredient(
            name = "butter",
            amounts = listOf(volumeMeasurement(1.0, "cup"))
        )
        assertEquals("2 cups butter", ingredient.format(scale = 2.0))
    }

    @Test
    fun `format half quantity`() {
        val ingredient = Ingredient(
            name = "milk",
            amounts = listOf(volumeMeasurement(0.5, "cup"))
        )
        assertEquals("1/2 cup milk", ingredient.format())
    }

    @Test
    fun `format quarter quantity`() {
        val ingredient = Ingredient(
            name = "vanilla",
            amounts = listOf(volumeMeasurement(0.25, "teaspoon"))
        )
        assertEquals("1/4 teaspoon vanilla", ingredient.format())
    }

    @Test
    fun `format mixed number`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(volumeMeasurement(2.5, "cups"))
        )
        assertEquals("2 1/2 cups flour", ingredient.format())
    }

    @Test
    fun `format third quantity`() {
        val ingredient = Ingredient(
            name = "oil",
            amounts = listOf(volumeMeasurement(0.33, "cup"))
        )
        assertEquals("1/3 cup oil", ingredient.format())
    }

    @Test
    fun `format two thirds quantity`() {
        val ingredient = Ingredient(
            name = "water",
            amounts = listOf(volumeMeasurement(0.66, "cup"))
        )
        assertEquals("2/3 cup water", ingredient.format())
    }

    @Test
    fun `format three quarters quantity`() {
        val ingredient = Ingredient(
            name = "cream",
            amounts = listOf(volumeMeasurement(0.75, "cup"))
        )
        assertEquals("3/4 cup cream", ingredient.format())
    }

    @Test
    fun `format scaling with fractions`() {
        val ingredient = Ingredient(
            name = "sugar",
            amounts = listOf(volumeMeasurement(1.0, "cup"))
        )
        // 1 cup * 0.5 = 0.5 cup = 1/2 cup
        assertEquals("1/2 cup sugar", ingredient.format(scale = 0.5))
    }

    @Test
    fun `format with alternates`() {
        val alternate = Ingredient(
            name = "table salt",
            amounts = listOf(volumeMeasurement(0.5, "teaspoon"))
        )
        val ingredient = Ingredient(
            name = "kosher salt",
            amounts = listOf(volumeMeasurement(1.0, "teaspoon")),
            alternates = listOf(alternate)
        )
        assertEquals("1 teaspoon kosher salt", ingredient.format())
    }

    @Test
    fun `alternate formats correctly with scaling`() {
        val alternate = Ingredient(
            name = "table salt",
            amounts = listOf(volumeMeasurement(0.5, "teaspoon"))
        )
        assertEquals("1 teaspoon table salt", alternate.format(scale = 2.0))
    }

    @Test
    fun `ingredient with multiple alternates`() {
        val alternates = listOf(
            Ingredient(
                name = "table salt",
                amounts = listOf(volumeMeasurement(0.5, "teaspoon"))
            ),
            Ingredient(
                name = "sea salt",
                amounts = listOf(volumeMeasurement(0.75, "teaspoon"))
            )
        )
        val ingredient = Ingredient(
            name = "kosher salt",
            amounts = listOf(volumeMeasurement(1.0, "teaspoon")),
            alternates = alternates
        )
        assertEquals("1 teaspoon kosher salt", ingredient.format())
        assertEquals(2, ingredient.alternates.size)
    }

    @Test
    fun `format with volume preference when both available`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(
                volumeMeasurement(2.0, "cups", isDefault = true),
                weightMeasurement(250.0, "grams", isDefault = false)
            )
        )
        assertEquals("2 cups flour", ingredient.format(preference = MeasurementPreference.VOLUME))
        assertEquals("250 grams flour", ingredient.format(preference = MeasurementPreference.WEIGHT))
        assertEquals("2 cups flour", ingredient.format(preference = MeasurementPreference.ORIGINAL))
    }

    @Test
    fun `format with weight preference when both available`() {
        val ingredient = Ingredient(
            name = "sugar",
            amounts = listOf(
                weightMeasurement(200.0, "grams", isDefault = true),
                volumeMeasurement(1.0, "cup", isDefault = false)
            )
        )
        assertEquals("1 cup sugar", ingredient.format(preference = MeasurementPreference.VOLUME))
        assertEquals("200 grams sugar", ingredient.format(preference = MeasurementPreference.WEIGHT))
        assertEquals("200 grams sugar", ingredient.format(preference = MeasurementPreference.ORIGINAL))
    }

    @Test
    fun `format falls back to default when preferred type not available`() {
        val ingredient = Ingredient(
            name = "eggs",
            amounts = listOf(countMeasurement(3.0, "large"))
        )
        // Should fall back to default (count) when volume or weight requested
        assertEquals("3 larges eggs", ingredient.format(preference = MeasurementPreference.VOLUME))
        assertEquals("3 larges eggs", ingredient.format(preference = MeasurementPreference.WEIGHT))
    }

    @Test
    fun `hasMultipleMeasurementTypes returns true when multiple types`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(
                volumeMeasurement(2.0, "cups"),
                weightMeasurement(250.0, "grams")
            )
        )
        assertTrue(ingredient.hasMultipleMeasurementTypes())
    }

    @Test
    fun `hasMultipleMeasurementTypes returns false when single type`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(volumeMeasurement(2.0, "cups"))
        )
        assertFalse(ingredient.hasMultipleMeasurementTypes())
    }

    @Test
    fun `availableMeasurementTypes returns correct set`() {
        val ingredient = Ingredient(
            name = "flour",
            amounts = listOf(
                volumeMeasurement(2.0, "cups"),
                weightMeasurement(250.0, "grams")
            )
        )
        assertEquals(setOf(MeasurementType.VOLUME, MeasurementType.WEIGHT), ingredient.availableMeasurementTypes())
    }
}
