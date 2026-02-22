package com.lionotter.recipes.domain

/**
 * Per-user resource limits enforced client-side.
 *
 * These limits prevent excessive Firebase usage. Size limits are also enforced
 * server-side via Firebase security rules (see README.md for rule configuration).
 */
object ResourceLimits {
    /** Maximum number of recipes a user can store. */
    const val MAX_RECIPES = 1_000

    /** Maximum number of meal plan entries a user can store. */
    const val MAX_MEAL_PLANS = 5_000

    /** Maximum number of images a user can store in Firebase Storage. */
    const val MAX_IMAGES = 1_000

    /** Maximum size of a single recipe document in bytes (50 KB). */
    const val MAX_RECIPE_SIZE_BYTES = 50 * 1024L

    /** Maximum size of a single meal plan document in bytes (10 KB). */
    const val MAX_MEAL_PLAN_SIZE_BYTES = 10 * 1024L

    /** Maximum size of a single image file in bytes (5 MB). */
    const val MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024L
}
