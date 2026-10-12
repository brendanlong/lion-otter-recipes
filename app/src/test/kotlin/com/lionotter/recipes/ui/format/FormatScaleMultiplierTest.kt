package com.lionotter.recipes.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatScaleMultiplierTest {

    @Test
    fun `whole multipliers have no decimals`() {
        assertEquals("1×", formatScaleMultiplier(1.0))
        assertEquals("2×", formatScaleMultiplier(2.0))
        assertEquals("10×", formatScaleMultiplier(10.0))
    }

    @Test
    fun `common fractions use fraction glyphs`() {
        assertEquals("¼×", formatScaleMultiplier(0.25))
        assertEquals("½×", formatScaleMultiplier(0.5))
        assertEquals("¾×", formatScaleMultiplier(0.75))
        assertEquals("1½×", formatScaleMultiplier(1.5))
        assertEquals("2¼×", formatScaleMultiplier(2.25))
    }

    @Test
    fun `other values use up to two decimals`() {
        assertEquals("1.2×", formatScaleMultiplier(1.2))
        assertEquals("0.33×", formatScaleMultiplier(1.0 / 3))
        assertEquals("2×", formatScaleMultiplier(1.999))
    }

    @Test
    fun `decimal separator does not depend on the locale`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.33×", formatScaleMultiplier(1.0 / 3))
        } finally {
            Locale.setDefault(default)
        }
    }
}
