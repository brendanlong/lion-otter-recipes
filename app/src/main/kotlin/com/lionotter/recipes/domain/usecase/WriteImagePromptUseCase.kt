package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.ImagePromptWriter
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Has the edit model write an image prompt for [Recipe]s that don't have one (it isn't saved here). */
class WriteImagePromptUseCase @Inject constructor(
    private val writer: ImagePromptWriter,
    private val aiSettings: AiSettings
) {
    suspend fun execute(recipe: Recipe): Outcome<String> {
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) return Outcome.Failure(RecipeError.NoApiKey)
        val model = AiModels.normalizeModel(aiSettings.editModel.first(), AiModels.DEFAULT_EDIT_MODEL)
        return writer.writePrompt(RecipeMarkdownFormatter.format(recipe), apiKey, model)
    }
}
