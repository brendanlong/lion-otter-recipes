package com.lionotter.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractFirstUrlTest {

    @Test
    fun `bare url is returned unchanged`() {
        assertEquals(
            "https://example.com/recipes/pasta?id=1&x=%20y#step-2",
            extractFirstUrl("https://example.com/recipes/pasta?id=1&x=%20y#step-2")
        )
    }

    @Test
    fun `url after title on same line is extracted`() {
        assertEquals(
            "https://www.seriouseats.com/best-chili",
            extractFirstUrl("The Best Chili Recipe https://www.seriouseats.com/best-chili")
        )
    }

    @Test
    fun `url on following line is extracted`() {
        assertEquals(
            "http://example.com/a",
            extractFirstUrl("Check this out!\nhttp://example.com/a\nvia SomeApp")
        )
    }

    @Test
    fun `trailing punctuation is trimmed`() {
        assertEquals(
            "https://example.com/recipe",
            extractFirstUrl("Try this (https://example.com/recipe).")
        )
    }

    @Test
    fun `first of several urls is used`() {
        assertEquals(
            "https://a.example.com/1",
            extractFirstUrl("https://a.example.com/1 and https://b.example.com/2")
        )
    }

    @Test
    fun `text without a link returns null`() {
        assertNull(extractFirstUrl("Grandma's lasagna: layer noodles, sauce, cheese"))
        assertNull(extractFirstUrl("https://"))
    }
}
