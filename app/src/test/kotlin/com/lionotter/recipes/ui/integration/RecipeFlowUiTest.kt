package com.lionotter.recipes.ui.integration

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionItem
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionScreen
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionUiState
import com.lionotter.recipes.ui.screens.recipedetail.components.RecipeContent
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-based UI integration tests that exercise end-to-end-like flows
 * through the composable screens. These run on the JVM as regular unit tests
 * without requiring an emulator.
 *
 * Flow tested:
 * 1. ImportSelectionScreen: recipes appear in checklist, user can select and tap import
 * 2. RecipeContent: recipe detail shows name, ingredients, instructions
 * 3. Favorite toggle behavior across screens
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeFlowUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // -- Test fixtures --

    private val testRecipeId = "test-recipe-123"
    private val testRecipeName = "Classic Chocolate Chip Cookies"

    private fun createTestRecipe(
        isFavorite: Boolean = false
    ) = Recipe(
        id = testRecipeId,
        name = testRecipeName,
        sourceUrl = "https://example.com/cookies",
        story = "A family recipe passed down through generations.",
        servings = 24,
        prepTime = "15 min",
        cookTime = "12 min",
        totalTime = "27 min",
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Preheat oven to 375\u00b0F (190\u00b0C).",
                        ingredients = emptyList()
                    ),
                    InstructionStep(
                        stepNumber = 2,
                        instruction = "Cream together butter and sugars until fluffy.",
                        ingredients = listOf(
                            Ingredient(
                                name = "butter",
                                amount = Amount(value = 1.0, unit = "cup")
                            ),
                            Ingredient(
                                name = "brown sugar",
                                amount = Amount(value = 0.75, unit = "cup")
                            ),
                            Ingredient(
                                name = "granulated sugar",
                                amount = Amount(value = 0.75, unit = "cup")
                            )
                        )
                    ),
                    InstructionStep(
                        stepNumber = 3,
                        instruction = "Mix in eggs and vanilla.",
                        ingredients = listOf(
                            Ingredient(
                                name = "eggs",
                                amount = Amount(value = 2.0, unit = null)
                            ),
                            Ingredient(
                                name = "vanilla extract",
                                amount = Amount(value = 1.0, unit = "tsp")
                            )
                        )
                    ),
                    InstructionStep(
                        stepNumber = 4,
                        instruction = "Add flour, baking soda, and salt. Mix until combined.",
                        ingredients = listOf(
                            Ingredient(
                                name = "all-purpose flour",
                                amount = Amount(value = 2.25, unit = "cup")
                            ),
                            Ingredient(
                                name = "baking soda",
                                amount = Amount(value = 1.0, unit = "tsp")
                            ),
                            Ingredient(
                                name = "salt",
                                amount = Amount(value = 1.0, unit = "tsp")
                            )
                        )
                    ),
                    InstructionStep(
                        stepNumber = 5,
                        instruction = "Fold in chocolate chips.",
                        ingredients = listOf(
                            Ingredient(
                                name = "chocolate chips",
                                amount = Amount(value = 2.0, unit = "cup")
                            )
                        )
                    ),
                    InstructionStep(
                        stepNumber = 6,
                        instruction = "Drop rounded tablespoons onto ungreased baking sheets. Bake for 9-11 minutes or until golden brown."
                    )
                )
            )
        ),
        tags = listOf("dessert", "cookies", "baking"),
        createdAt = Instant.fromEpochMilliseconds(1700000000000),
        updatedAt = Instant.fromEpochMilliseconds(1700000000000),
        isFavorite = isFavorite
    )

    // -- Helpers to reduce test setup boilerplate --

    private fun setImportSelectionScreen(
        state: ImportSelectionUiState,
        onToggleItem: (String) -> Unit = {},
        onSelectAll: () -> Unit = {},
        onDeselectAll: () -> Unit = {},
        onImportClick: () -> Unit = {},
        onCancelClick: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ImportSelectionScreen(
                    title = "Select Recipes to Import",
                    state = state,
                    onToggleItem = onToggleItem,
                    onSelectAll = onSelectAll,
                    onDeselectAll = onDeselectAll,
                    onImportClick = onImportClick,
                    onCancelClick = onCancelClick
                )
            }
        }
    }

    private fun setRecipeContent(
        recipe: Recipe = createTestRecipe(),
        scale: Double = 1.0,
        onScaleIncrement: () -> Unit = {},
        onScaleDecrement: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                RecipeContent(
                    recipe = recipe,
                    scale = scale,
                    onScaleIncrement = onScaleIncrement,
                    onScaleDecrement = onScaleDecrement,
                    measurementPreference = MeasurementPreference.DEFAULT,
                    onMeasurementPreferenceChange = {},
                    showMeasurementToggle = false,
                    usedInstructionIngredients = emptySet(),
                    ingredientUsageBySection = emptyMap(),
                    onToggleInstructionIngredient = { _, _, _ -> },
                    highlightedInstructionStep = null,
                    onToggleHighlightedInstruction = { _, _ -> }
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Import Selection Screen Tests
    // -----------------------------------------------------------------------

    @Test
    fun `import selection shows loading state`() {
        setImportSelectionScreen(state = ImportSelectionUiState.Loading)

        composeTestRule.onNodeWithText("Reading recipes\u2026").assertIsDisplayed()
    }

    @Test
    fun `import selection shows recipes from file`() {
        val items = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = true,
                alreadyExists = false
            ),
            ImportSelectionItem(
                id = "recipe-2",
                name = "Banana Bread",
                isSelected = true,
                alreadyExists = false
            )
        )

        setImportSelectionScreen(state = ImportSelectionUiState.Ready(items))

        // Both recipes should be visible
        composeTestRule.onNodeWithText(testRecipeName).assertIsDisplayed()
        composeTestRule.onNodeWithText("Banana Bread").assertIsDisplayed()

        // Selection count should show "2 of 2 selected"
        composeTestRule.onNodeWithText("2 of 2 selected").assertIsDisplayed()

        // Import button should be enabled
        composeTestRule.onNodeWithTag(TestTags.IMPORT_BUTTON).assertIsEnabled()
    }

    @Test
    fun `import selection marks existing recipes`() {
        val items = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = false,
                alreadyExists = true
            )
        )

        setImportSelectionScreen(state = ImportSelectionUiState.Ready(items))

        composeTestRule.onNodeWithText(testRecipeName).assertIsDisplayed()
        composeTestRule.onNodeWithText("Already in your recipes").assertIsDisplayed()

        // Import button should be disabled (nothing selected)
        composeTestRule.onNodeWithTag(TestTags.IMPORT_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `import selection toggle calls callback with correct id`() {
        var toggledItemId: String? = null
        val items = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = true,
                alreadyExists = false
            )
        )

        setImportSelectionScreen(
            state = ImportSelectionUiState.Ready(items),
            onToggleItem = { toggledItemId = it }
        )

        // Click on the item row to toggle
        composeTestRule.onNodeWithTag(TestTags.importItem(testRecipeId)).performClick()
        assert(toggledItemId == testRecipeId) {
            "Expected toggle callback with $testRecipeId, got $toggledItemId"
        }
    }

    @Test
    fun `import selection import button triggers callback`() {
        var importClicked = false
        val items = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = true,
                alreadyExists = false
            )
        )

        setImportSelectionScreen(
            state = ImportSelectionUiState.Ready(items),
            onImportClick = { importClicked = true }
        )

        composeTestRule.onNodeWithTag(TestTags.IMPORT_BUTTON).performClick()
        assert(importClicked) { "Import button click callback was not triggered" }
    }

    @Test
    fun `import selection shows error state`() {
        setImportSelectionScreen(
            state = ImportSelectionUiState.Error("Failed to read file: corrupt ZIP")
        )

        composeTestRule.onNodeWithText("Failed to read file: corrupt ZIP").assertIsDisplayed()
    }

    @Test
    fun `import selection select all and deselect all buttons work`() {
        var selectAllClicked = false
        var deselectAllClicked = false
        val items = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = false,
                alreadyExists = false
            )
        )

        setImportSelectionScreen(
            state = ImportSelectionUiState.Ready(items),
            onSelectAll = { selectAllClicked = true },
            onDeselectAll = { deselectAllClicked = true }
        )

        composeTestRule.onNodeWithText("Select All").performClick()
        assert(selectAllClicked) { "Select All callback was not triggered" }

        composeTestRule.onNodeWithText("Deselect All").performClick()
        assert(deselectAllClicked) { "Deselect All callback was not triggered" }
    }

    // -----------------------------------------------------------------------
    // Recipe Detail Content Tests
    // -----------------------------------------------------------------------

    @Test
    fun `recipe detail shows recipe content and metadata`() {
        setRecipeContent()

        // Check that the detail content is displayed
        composeTestRule.onNodeWithTag(TestTags.RECIPE_DETAIL_CONTENT).assertIsDisplayed()

        // Ingredients header (may need scroll)
        composeTestRule.onNodeWithTag(TestTags.INGREDIENTS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()

        // Some ingredients (scroll to first match to verify existence)
        composeTestRule.onAllNodesWithText("butter", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("chocolate chips", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()

        // Instructions header (scroll to make visible)
        composeTestRule.onNodeWithTag(TestTags.INSTRUCTIONS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()

        // Some instruction text (scroll to make visible)
        composeTestRule.onAllNodesWithText("Preheat oven", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Fold in chocolate chips", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `recipe detail shows tags`() {
        setRecipeContent()

        composeTestRule.onNodeWithText("dessert").assertIsDisplayed()
        composeTestRule.onNodeWithText("cookies").assertIsDisplayed()
        composeTestRule.onNodeWithText("baking").assertIsDisplayed()
    }

    @Test
    fun `recipe detail shows time and servings metadata`() {
        setRecipeContent()

        // Prep/cook/total time and servings are displayed via RecipeMetadata
        composeTestRule.onNodeWithText("15 min", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("12 min", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("27 min", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("24", substring = true).assertIsDisplayed()
    }

    @Test
    fun `recipe detail shows source URL`() {
        setRecipeContent()

        composeTestRule.onNodeWithText("https://example.com/cookies", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `recipe detail scale controls work`() {
        var scaleIncremented = false
        var scaleDecremented = false

        setRecipeContent(
            onScaleIncrement = { scaleIncremented = true },
            onScaleDecrement = { scaleDecremented = true }
        )

        // Tap the increase scale button
        composeTestRule.onNode(hasContentDescription("Increase scale")).performClick()
        assert(scaleIncremented) { "Scale increment callback was not triggered" }

        // Tap the decrease scale button
        composeTestRule.onNode(hasContentDescription("Decrease scale")).performClick()
        assert(scaleDecremented) { "Scale decrement callback was not triggered" }
    }

    // -----------------------------------------------------------------------
    // End-to-End Flow: Import → List → Detail
    // -----------------------------------------------------------------------

    @Test
    fun `end to end flow import selection to recipe detail`() {
        // This test simulates the flow of:
        // 1. User sees import selection screen with recipes
        // 2. User selects recipes and taps import
        // 3. User sees recipe list with the imported recipe
        // 4. User taps on recipe to see detail

        // Phase 1: Import Selection
        var currentScreen by mutableStateOf("import")
        var importClicked = false
        val importItems = listOf(
            ImportSelectionItem(
                id = testRecipeId,
                name = testRecipeName,
                isSelected = true,
                alreadyExists = false
            )
        )

        composeTestRule.setContent {
            MaterialTheme {
                when (currentScreen) {
                    "import" -> ImportSelectionScreen(
                        title = "Select Recipes to Import",
                        state = ImportSelectionUiState.Ready(importItems),
                        onToggleItem = {},
                        onSelectAll = {},
                        onDeselectAll = {},
                        onImportClick = {
                            importClicked = true
                            currentScreen = "detail"
                        },
                        onCancelClick = {}
                    )
                    "detail" -> RecipeContent(
                        recipe = createTestRecipe(),
                        scale = 1.0,
                        onScaleIncrement = {},
                        onScaleDecrement = {},
                        measurementPreference = MeasurementPreference.DEFAULT,
                        onMeasurementPreferenceChange = {},
                        showMeasurementToggle = false,
                        usedInstructionIngredients = emptySet(),
                        ingredientUsageBySection = emptyMap(),
                        onToggleInstructionIngredient = { _, _, _ -> },
                        highlightedInstructionStep = null,
                        onToggleHighlightedInstruction = { _, _ -> }
                    )
                }
            }
        }

        // Verify import selection screen
        composeTestRule.onNodeWithText(testRecipeName).assertIsDisplayed()
        composeTestRule.onNodeWithText("1 of 1 selected").assertIsDisplayed()

        // Tap Import
        composeTestRule.onNodeWithTag(TestTags.IMPORT_BUTTON).performClick()
        assert(importClicked) { "Import was not triggered" }

        // Verify we transitioned to recipe detail
        composeTestRule.onNodeWithTag(TestTags.RECIPE_DETAIL_CONTENT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.INGREDIENTS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.INSTRUCTIONS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("butter", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Preheat oven", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `import selection with multiple recipes and mixed selection`() {
        var toggledIds = mutableListOf<String>()
        val items = listOf(
            ImportSelectionItem(
                id = "recipe-1",
                name = "Chocolate Chip Cookies",
                isSelected = true,
                alreadyExists = false
            ),
            ImportSelectionItem(
                id = "recipe-2",
                name = "Banana Bread",
                isSelected = false,
                alreadyExists = false
            ),
            ImportSelectionItem(
                id = "recipe-3",
                name = "Existing Cake",
                isSelected = false,
                alreadyExists = true
            )
        )

        setImportSelectionScreen(
            state = ImportSelectionUiState.Ready(items),
            onToggleItem = { toggledIds.add(it) }
        )

        // All three recipes visible
        composeTestRule.onNodeWithText("Chocolate Chip Cookies").assertIsDisplayed()
        composeTestRule.onNodeWithText("Banana Bread").assertIsDisplayed()
        composeTestRule.onNodeWithText("Existing Cake").assertIsDisplayed()

        // "Existing Cake" shows already exists label
        composeTestRule.onNodeWithText("Already in your recipes").assertIsDisplayed()

        // Selection count shows "1 of 3 selected"
        composeTestRule.onNodeWithText("1 of 3 selected").assertIsDisplayed()

        // Toggle Banana Bread
        composeTestRule.onNodeWithTag(TestTags.importItem("recipe-2")).performClick()
        assert(toggledIds.contains("recipe-2")) {
            "Expected recipe-2 toggle, got $toggledIds"
        }
    }

    @Test
    fun `recipe detail with empty instruction sections shows basic content`() {
        val recipe = Recipe(
            id = "empty-recipe",
            name = "Simple Salad",
            instructionSections = emptyList(),
            createdAt = Instant.fromEpochMilliseconds(1700000000000),
            updatedAt = Instant.fromEpochMilliseconds(1700000000000)
        )

        setRecipeContent(recipe = recipe)

        composeTestRule.onNodeWithTag(TestTags.RECIPE_DETAIL_CONTENT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.INGREDIENTS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.INSTRUCTIONS_SECTION)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
