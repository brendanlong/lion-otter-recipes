package com.lionotter.recipes.util

/** The [T] constant named [name], or null if there is none (e.g. a value stored by a newer app version). */
inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }
