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

    /** Longest image prompt kept on a recipe or sent for an image (the AI writes about 80 words). */
    const val MAX_IMAGE_PROMPT_CHARS = 2000

    /** Maximum size of a single image file in bytes (5 MB). */
    const val MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024L

    /** Recipe page HTML longer than this (downloaded or captured in the browser) is cut off. */
    const val MAX_PAGE_HTML_CHARS = 5 * 1024 * 1024

    /** Largest HTML file that can be imported as a recipe (5 MB). */
    const val MAX_HTML_FILE_BYTES = 5 * 1024 * 1024L

    /**
     * Largest Markdown or text file that can be imported as a recipe (200 KB). It is sent to the
     * AI as is, so this keeps it well within what the AI accepts.
     */
    const val MAX_TEXT_FILE_BYTES = 200 * 1024L

    /**
     * Largest photo that can be imported as a recipe (25 MB, like other local images). Photos are
     * scaled down before they are sent to the AI.
     */
    const val MAX_PHOTO_FILE_BYTES = 25 * 1024 * 1024L

    /** Most photos one recipe can be imported from (e.g. the pages of a cookbook recipe). */
    const val MAX_PHOTOS_PER_RECIPE = 5

    /**
     * Largest PDF that can be imported as a recipe (10 MB). PDFs are sent to the AI as is, and
     * base64 makes them a third larger, so this stays well within the API's 32 MB request limit.
     */
    const val MAX_PDF_FILE_BYTES = 10 * 1024 * 1024L

    /** Most pages a PDF imported as a recipe can have. */
    const val MAX_PDF_PAGES = 20
}
