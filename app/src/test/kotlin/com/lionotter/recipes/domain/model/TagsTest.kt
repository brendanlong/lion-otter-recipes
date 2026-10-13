package com.lionotter.recipes.domain.model

import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class TagsTest {

    private fun recipe(id: String, name: String = id, vararg tags: String) = Recipe(
        id = id,
        name = name,
        tags = tags.toList(),
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `tag key ignores case and surrounding whitespace`() {
        assertEquals("dessert", tagKey(" Dessert "))
        assertEquals(tagKey("dessert"), tagKey("DESSERT"))
    }

    @Test
    fun `recipe tagged with padded spelling matches the chip it contributes to`() {
        val padded = recipe("a", "Cake", " Dessert")
        val plain = recipe("b", "Pie", "dessert")

        // The chip shown for these recipes is built by GetTagsUseCase from the same tags
        val chip = GetTagsUseCase().buildTagMap(listOf(padded, plain)).keys.single()

        assertTrue(padded.hasTag(chip))
        assertTrue(plain.hasTag(chip))
        assertTrue(padded.hasTag("dessert"))
        assertFalse(padded.hasTag("dinner"))
    }

    @Test
    fun `search matches name or tag ignoring case`() {
        val cake = recipe("a", "Chocolate Cake", "Dessert")
        assertTrue(cake.matchesSearch(""))
        assertTrue(cake.matchesSearch("chocolate"))
        assertTrue(cake.matchesSearch("dess"))
        assertFalse(cake.matchesSearch("soup"))
    }

    @Test
    fun `favorites are added in the given spelling and removed ignoring case`() {
        val favorites = setOf("Dessert", "Dinner")

        assertEquals(setOf("Dinner", "dessert"), favorites.withFavorite(" dessert ", true))
        assertEquals(setOf("Dinner"), favorites.withFavorite("DESSERT", false))
        assertEquals(favorites, favorites.withFavorite("  ", true))
    }
}
