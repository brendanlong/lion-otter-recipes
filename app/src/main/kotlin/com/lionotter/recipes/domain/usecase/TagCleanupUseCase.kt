package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.applyTagChanges
import com.lionotter.recipes.domain.model.planTagChanges
import com.lionotter.recipes.domain.model.tagUsage
import com.lionotter.recipes.domain.model.withTagChanges
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.domain.port.TagOrganizer
import com.lionotter.recipes.domain.port.TagPreferences
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cleans up the user's tags: asks the AI (with the edit model) which tags to rename, merge or
 * delete, and applies the changes the user accepts to every recipe that carries them (and to the
 * favorite tags).
 */
class TagCleanupUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val tagOrganizer: TagOrganizer,
    private val aiSettings: AiSettings,
    private val tagPreferences: TagPreferences
) {
    /** The AI's suggested changes; empty if there are no tags or nothing needs changing. */
    suspend fun suggestChanges(): Outcome<List<TagChange>> {
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) return Outcome.Failure(RecipeError.NoApiKey)

        val usage = try {
            tagUsage(recipeRepository.getAllRecipeTags().values)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Couldn't read the recipes' tags", e)
            return Outcome.Failure(RecipeError.Unexpected)
        }
        if (usage.isEmpty()) return Outcome.Success(emptyList())

        val request = TagCleanupRequest(
            tags = usage,
            apiKey = apiKey,
            model = AiModels.normalizeModel(aiSettings.editModel.first(), AiModels.DEFAULT_EDIT_MODEL),
            thinkingEnabled = aiSettings.thinkingEnabled.first()
        )
        return when (val result = tagOrganizer.suggestTagChanges(request)) {
            is Outcome.Success -> Outcome.Success(planTagChanges(usage, result.value))
            is Outcome.Failure -> result
        }
    }

    /**
     * Applies [changes] to every recipe carrying one of the tags and returns how many recipes
     * changed. Favorites follow renamed and merged tags, and deleted tags stop being favorites;
     * if the favorites can't be updated nothing is changed. Runs to completion even if the caller is cancelled, so a batch is never left half
     * applied by leaving the screen. The writes themselves follow the repository's write contract
     * (applied locally at once, sync failures reported app-wide).
     */
    suspend fun applyChanges(changes: List<TagChange>): Outcome<Int> = withContext(NonCancellable) {
        if (changes.isEmpty()) return@withContext Outcome.Success(0)
        val byKey = changes.associateBy { it.key }
        val recipeTags = try {
            recipeRepository.getAllRecipeTags()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Couldn't read the recipes' tags to apply ${changes.size} changes", e)
            return@withContext Outcome.Failure(RecipeError.Unexpected)
        }
        try {
            tagPreferences.updateFavoriteTags { it.withTagChanges(byKey) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Couldn't update the favorite tags for ${changes.size} changes", e)
            return@withContext Outcome.Failure(RecipeError.Unexpected)
        }
        var updated = 0
        for ((recipeId, tags) in recipeTags) {
            val newTags = applyTagChanges(tags, byKey)
            if (newTags != tags) {
                recipeRepository.setTags(recipeId, newTags)
                updated++
            }
        }
        Outcome.Success(updated)
    }

    private companion object {
        const val TAG = "TagCleanupUseCase"
    }
}
