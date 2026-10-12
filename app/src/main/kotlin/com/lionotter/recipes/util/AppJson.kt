package com.lionotter.recipes.util

import kotlinx.serialization.json.Json

/**
 * The app-wide Json configuration (also provided through Hilt by AppModule). Lenient and
 * tolerant of unknown keys so data written by newer app versions still loads.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}
