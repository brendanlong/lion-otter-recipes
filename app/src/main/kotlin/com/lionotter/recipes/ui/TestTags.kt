package com.lionotter.recipes.ui

/**
 * Semantic test tags for Compose UI test targeting.
 * Using a central object avoids typo mismatches between production and test code.
 */
object TestTags {
    // Recipe detail screen
    const val RECIPE_DETAIL_CONTENT = "recipe_detail_content"
    const val INGREDIENTS_SECTION = "ingredients_section"
    const val INSTRUCTIONS_SECTION = "instructions_section"

    // Import selection screen
    const val IMPORT_SELECTION_LIST = "import_selection_list"
    const val IMPORT_BUTTON = "import_button"
    fun importItem(itemId: String) = "import_item_$itemId"
}
