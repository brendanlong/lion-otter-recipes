package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UnitSystemTest {

    @Test
    fun `localeDefault returns CUSTOMARY for US locale`() {
        assertEquals(UnitSystem.CUSTOMARY, UnitSystem.localeDefault(Locale.US))
    }

    @Test
    fun `localeDefault returns CUSTOMARY for Liberia locale`() {
        assertEquals(UnitSystem.CUSTOMARY, UnitSystem.localeDefault(Locale("en", "LR")))
    }

    @Test
    fun `localeDefault returns CUSTOMARY for Myanmar locale`() {
        assertEquals(UnitSystem.CUSTOMARY, UnitSystem.localeDefault(Locale("my", "MM")))
    }

    @Test
    fun `localeDefault returns METRIC for UK locale`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.localeDefault(Locale.UK))
    }

    @Test
    fun `localeDefault returns METRIC for France locale`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.localeDefault(Locale.FRANCE))
    }

    @Test
    fun `localeDefault returns METRIC for Germany locale`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.localeDefault(Locale.GERMANY))
    }

    @Test
    fun `localeDefault returns METRIC for Japan locale`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.localeDefault(Locale.JAPAN))
    }

    @Test
    fun `localeDefault returns METRIC for Canada locale`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.localeDefault(Locale.CANADA))
    }
}
