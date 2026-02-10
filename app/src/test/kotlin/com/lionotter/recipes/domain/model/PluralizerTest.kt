package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize
import org.junit.Assert.assertEquals
import org.junit.Test

class PluralizerTest {
    @Test
    fun `test pluralize cup`() {
        assertEquals("cup", "cup".pluralize(1))
        assertEquals("cups", "cup".pluralize(2))
    }

    @Test
    fun `test singularize cups`() {
        assertEquals("cup", "cups".singularize())
    }

    @Test
    fun `test round trip`() {
        assertEquals("cups", "cups".singularize().pluralize(2))
    }

    @Test
    fun `test irregular plurals`() {
        assertEquals("feet", "foot".pluralize(2))
        assertEquals("foot", "feet".singularize())
        assertEquals("teeth", "tooth".pluralize(2))
        assertEquals("geese", "goose".pluralize(2))
    }

    @Test
    fun `test uncountables`() {
        assertEquals("fish", "fish".pluralize(1))
        assertEquals("fish", "fish".pluralize(2))
        assertEquals("sheep", "sheep".pluralize(2))
        assertEquals("rice", "rice".pluralize(2))
    }

    @Test
    fun `test unit abbreviations are not pluralized`() {
        // Abbreviations should remain unchanged regardless of count
        val abbreviations = listOf("mg", "g", "kg", "oz", "lb", "mL", "L", "tsp", "tbsp", "fl_oz", "gal")
        for (abbr in abbreviations) {
            assertEquals("$abbr should not change for count 1", abbr, abbr.singularize().pluralize(1))
            assertEquals("$abbr should not change for count 2", abbr, abbr.singularize().pluralize(2))
        }
    }

    @Test
    fun `test words that should still pluralize`() {
        // Full words like cup, pint, quart should still pluralize
        assertEquals("cups", "cup".singularize().pluralize(2))
        assertEquals("pints", "pint".singularize().pluralize(2))
        assertEquals("quarts", "quart".singularize().pluralize(2))
    }

    @Test
    fun `test case preservation`() {
        assertEquals("Cups", "Cup".pluralize(2))
        assertEquals("CUPS", "CUP".pluralize(2))
    }
}
