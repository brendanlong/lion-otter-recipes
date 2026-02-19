package com.lionotter.recipes.ui

/**
 * Semantic test tags for Compose UI test targeting.
 * Using a central object avoids typo mismatches between production and test code.
 */
object TestTags {
    // Recipe list screen
    const val RECIPE_LIST = "recipe_list"
    const val EMPTY_STATE = "empty_state"
    fun recipeCard(recipeId: String) = "recipe_card_$recipeId"
    fun favoriteButton(recipeId: String) = "favorite_button_$recipeId"

    // Recipe detail screen
    const val RECIPE_DETAIL_CONTENT = "recipe_detail_content"
    const val RECIPE_DETAIL_NAME = "recipe_detail_name"
    const val RECIPE_DETAIL_FAVORITE = "recipe_detail_favorite"
    const val INGREDIENTS_SECTION = "ingredients_section"
    const val INSTRUCTIONS_SECTION = "instructions_section"

    // Import selection screen
    const val IMPORT_SELECTION_LIST = "import_selection_list"
    const val IMPORT_BUTTON = "import_button"
    fun importItem(itemId: String) = "import_item_$itemId"
    fun importCheckbox(itemId: String) = "import_checkbox_$itemId"
}
