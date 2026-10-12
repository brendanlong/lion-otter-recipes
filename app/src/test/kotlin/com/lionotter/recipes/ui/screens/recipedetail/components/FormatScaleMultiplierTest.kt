package com.lionotter.recipes.ui.screens.recipedetail.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatScaleMultiplierTest {

    @Test
    fun `whole multipliers have no decimals`() {
        assertEquals("1×", formatScaleMultiplier(1.0))
        assertEquals("10×", formatScaleMultiplier(10.0))
    }

    @Test
    fun `common fractions use fraction glyphs`() {
        assertEquals("¼×", formatScaleMultiplier(0.25))
        assertEquals("½×", formatScaleMultiplier(0.5))
        assertEquals("1½×", formatScaleMultiplier(1.5))
    }

    @Test
    fun `other values use up to two decimals`() {
        assertEquals("1.2×", formatScaleMultiplier(1.2))
        assertEquals("0.33×", formatScaleMultiplier(1.0 / 3))
    }
}
