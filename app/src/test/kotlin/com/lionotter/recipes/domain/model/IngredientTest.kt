package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IngredientTest {

    @Test
    fun `format with all fields`() {
        val ingredient = Ingredient(
            name = "flour",
            quantity = 2.0,
            unit = "cups",
            notes = "sifted"
        )
        assertEquals("2 cups flour, sifted", ingredient.format())
    }

    @Test
    fun `format without notes`() {
        val ingredient = Ingredient(
            name = "sugar",
            quantity = 1.0,
            unit = "cup",
            notes = null
        )
        assertEquals("1 cup sugar", ingredient.format())
    }

    @Test
    fun `format without unit`() {
        val ingredient = Ingredient(
            name = "eggs",
            quantity = 3.0,
            unit = null,
            notes = null
        )
        assertEquals("3 eggs", ingredient.format())
    }

    @Test
    fun `format without quantity`() {
        val ingredient = Ingredient(
            name = "salt",
            quantity = null,
            unit = null,
            notes = "to taste"
        )
        assertEquals("salt, to taste", ingredient.format())
    }

    @Test
    fun `format with scaling`() {
        val ingredient = Ingredient(
            name = "butter",
            quantity = 1.0,
            unit = "cup",
            notes = null
        )
        assertEquals("2 cups butter", ingredient.format(scale = 2.0))
    }

    @Test
    fun `format half quantity`() {
        val ingredient = Ingredient(
            name = "milk",
            quantity = 0.5,
            unit = "cup",
            notes = null
        )
        assertEquals("1/2 cup milk", ingredient.format())
    }

    @Test
    fun `format quarter quantity`() {
        val ingredient = Ingredient(
            name = "vanilla",
            quantity = 0.25,
            unit = "tsp",
            notes = null
        )
        assertEquals("1/4 tsp vanilla", ingredient.format())
    }

    @Test
    fun `format mixed number`() {
        val ingredient = Ingredient(
            name = "flour",
            quantity = 2.5,
            unit = "cups",
            notes = null
        )
        assertEquals("2 1/2 cups flour", ingredient.format())
    }

    @Test
    fun `format third quantity`() {
        val ingredient = Ingredient(
            name = "oil",
            quantity = 0.33,
            unit = "cup",
            notes = null
        )
        assertEquals("1/3 cup oil", ingredient.format())
    }

    @Test
    fun `format two thirds quantity`() {
        val ingredient = Ingredient(
            name = "water",
            quantity = 0.66,
            unit = "cup",
            notes = null
        )
        assertEquals("2/3 cup water", ingredient.format())
    }

    @Test
    fun `format three quarters quantity`() {
        val ingredient = Ingredient(
            name = "cream",
            quantity = 0.75,
            unit = "cup",
            notes = null
        )
        assertEquals("3/4 cup cream", ingredient.format())
    }

    @Test
    fun `format scaling with fractions`() {
        val ingredient = Ingredient(
            name = "sugar",
            quantity = 1.0,
            unit = "cup",
            notes = null
        )
        // 1 cup * 0.5 = 0.5 cup = 1/2 cup
        assertEquals("1/2 cup sugar", ingredient.format(scale = 0.5))
    }
}
