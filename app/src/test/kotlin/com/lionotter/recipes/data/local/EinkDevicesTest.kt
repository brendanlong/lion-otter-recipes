package com.lionotter.recipes.data.local

import com.lionotter.recipes.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkDevicesTest {
    @Test
    fun `e-reader makers match on maker or brand, in any case`() {
        assertTrue(EinkDevices.isEink("ONYX", "MC_NOTE", "NoteAir3C"))
        assertTrue(EinkDevices.isEink("Onyx International Inc.".substringBefore(' '), "", ""))
        assertTrue(EinkDevices.isEink("unknown", "Boox", "Palma"))
        assertTrue(EinkDevices.isEink("Bigme", "Bigme", "HiBreak"))
        assertTrue(EinkDevices.isEink(" Meebook ", "", "M7"))
        assertTrue(EinkDevices.isEink("PocketBook", "", "InkPad"))
        assertTrue(EinkDevices.isEink("Mudita", "Mudita", "Kompakt"))
    }

    @Test
    fun `makers of ordinary screens match only on their e-ink models`() {
        assertTrue(EinkDevices.isEink("Hisense", "Hisense", "A9"))
        assertTrue(EinkDevices.isEink("Hisense", "Hisense", "A7 Pro"))
        assertFalse(EinkDevices.isEink("Hisense", "Hisense", "Infinity H50"))
        assertTrue(EinkDevices.isEink("Xiaomi", "Xiaomi", "InkPalm 5"))
        assertFalse(EinkDevices.isEink("Xiaomi", "Redmi", "23053RN02A"))
    }

    @Test
    fun `phones and tablets aren't e-ink`() {
        assertFalse(EinkDevices.isEink("Google", "google", "Pixel 4"))
        assertFalse(EinkDevices.isEink("samsung", "samsung", "SM-S918B"))
        assertFalse(EinkDevices.isEink("robolectric", "Android", "robolectric"))
    }

    @Test
    fun `the theme defaults to e-ink only on an e-ink device`() {
        assertEquals(ThemeMode.EINK, SettingsDataStore.themeModeOf(stored = null, einkDevice = true))
        assertEquals(ThemeMode.AUTO, SettingsDataStore.themeModeOf(stored = null, einkDevice = false))
    }

    @Test
    fun `a theme the user picked wins over the device default`() {
        assertEquals(ThemeMode.AUTO, SettingsDataStore.themeModeOf(stored = "AUTO", einkDevice = true))
        assertEquals(ThemeMode.LIGHT, SettingsDataStore.themeModeOf(stored = "LIGHT", einkDevice = true))
        assertEquals(ThemeMode.EINK, SettingsDataStore.themeModeOf(stored = "EINK", einkDevice = false))
    }

    @Test
    fun `an unknown stored theme falls back to the device default`() {
        assertEquals(ThemeMode.EINK, SettingsDataStore.themeModeOf(stored = "SEPIA", einkDevice = true))
        assertEquals(ThemeMode.AUTO, SettingsDataStore.themeModeOf(stored = "SEPIA", einkDevice = false))
    }
}
