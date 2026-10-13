package com.lionotter.recipes.data.local

import android.os.Build

/**
 * Recognizes e-readers and other e-ink devices. Android can't tell an app that its screen is
 * e-ink, so this goes by who made the device: makers that only make e-ink devices match on their
 * name alone, and makers that also make ordinary screens only for their e-ink models.
 */
object EinkDevices {
    private val EINK_MAKERS = setOf(
        "onyx", "boox", // Onyx Boox
        "boyue", "likebook", "meebook", // Boyue (Likebook, Meebook)
        "bigme",
        "mudita",
        "kobo", "tolino",
        "pocketbook",
        "remarkable",
        "ratta", "supernote",
        "moaan", "inkpalm",
    )

    /** Model name prefixes of the e-ink devices of makers that also make other screens. */
    private val EINK_MODELS = mapOf(
        "hisense" to listOf("a5", "a7", "a9", "touch"),
        "xiaomi" to listOf("inkpalm", "moaan"),
        "barnesandnoble" to listOf("bnrv", "nook glowlight"),
    )

    fun isEink(manufacturer: String, brand: String, model: String): Boolean {
        val makers = listOf(manufacturer, brand).map { it.trim().lowercase() }
        if (makers.any { it in EINK_MAKERS }) return true
        val normalizedModel = model.trim().lowercase()
        return makers.any { maker ->
            EINK_MODELS[maker].orEmpty().any { normalizedModel.startsWith(it) }
        }
    }

    /** Whether this device is one of them. */
    val isThisDevice: Boolean by lazy {
        isEink(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.MODEL.orEmpty())
    }
}
