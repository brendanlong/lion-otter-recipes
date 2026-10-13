package com.lionotter.recipes.domain

/** The result of a use case: a value, or a [RecipeError] saying why it failed. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: RecipeError) : Outcome<Nothing>
}
