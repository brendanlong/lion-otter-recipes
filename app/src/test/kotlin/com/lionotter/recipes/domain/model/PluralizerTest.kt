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

    @Test
    fun `food words singularize correctly`() {
        val expected = mapOf(
            "chilies" to "chili",
            "chilis" to "chili",
            "chillies" to "chilli",
            "molasses" to "molasses",
            "tomatoes" to "tomato",
            "potatoes" to "potato",
            "leaves" to "leaf",
            "bay leaves" to "bay leaf",
            "loaves" to "loaf",
            "halves" to "half",
            "knives" to "knife",
            "radishes" to "radish",
            "peaches" to "peach",
            "berries" to "berry",
            "anchovies" to "anchovy",
            "asparagus" to "asparagus",
            "couscous" to "couscous",
            "hummus" to "hummus",
            "swiss chard" to "swiss chard",
            "noodles" to "noodle",
            "peas" to "pea",
            "chickpeas" to "chickpea",
            "brussels sprouts" to "brussels sprout",
            "cloves" to "clove",
            "olives" to "olive",
            "dates" to "date",
            "cookies" to "cookie",
            "species" to "species",
            "chives" to "chive",
            "brownies" to "brownie",
            "mangoes" to "mango",
            "cherries" to "cherry"
        )
        for ((plural, singular) in expected) {
            assertEquals("singular of $plural", singular, plural.singularize())
        }
    }

    @Test
    fun `singular and plural forms normalize to the same key`() {
        val words = listOf("chili", "tomato", "leaf", "berry", "anchovy", "clove", "olive", "cookie", "molasses")
        for (word in words) {
            assertEquals("round trip for $word", word, word.pluralize(2).singularize())
        }
    }

    @Test
    fun `food words pluralize correctly`() {
        assertEquals("chilies", "chili".pluralize(2))
        assertEquals("tomatoes", "tomato".pluralize(2))
        assertEquals("leaves", "leaf".pluralize(2))
        assertEquals("cloves", "clove".pluralize(2))
        assertEquals("pinches", "pinch".pluralize(2))
        assertEquals("molasses", "molasses".pluralize(2))
        assertEquals("hummus", "hummus".pluralize(2))
    }
}
