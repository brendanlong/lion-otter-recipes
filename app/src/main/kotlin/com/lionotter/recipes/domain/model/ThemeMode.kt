package com.lionotter.recipes.domain.model

enum class ThemeMode {
    AUTO,
    LIGHT,
    DARK,

    /** Black on white with no tints or animations, for e-ink screens. */
    EINK;

    companion object {
        /** The theme until the user picks one: e-ink on an e-ink device. */
        fun defaultFor(einkDevice: Boolean): ThemeMode = if (einkDevice) EINK else AUTO
    }
}
