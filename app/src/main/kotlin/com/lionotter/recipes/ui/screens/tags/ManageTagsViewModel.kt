package com.lionotter.recipes.ui.screens.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.domain.model.planTagChanges
import com.lionotter.recipes.domain.model.tagKey
import com.lionotter.recipes.domain.model.tagKeys
import com.lionotter.recipes.domain.model.tagUsage
import com.lionotter.recipes.domain.port.TagPreferences
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.TagCleanupUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The AI's suggested tag changes, as far as they have got. */
sealed interface TagSuggestions {
    data object None : TagSuggestions
    data object Loading : TagSuggestions

    /** [selected] holds the [TagChange.key]s of the changes the user still wants. */
    data class Review(val changes: List<TagChange>, val selected: Set<String>) : TagSuggestions {
        val selectedChanges: List<TagChange> get() = changes.filter { it.key in selected }
    }
}

sealed interface ManageTagsUiState {
    data object Loading : ManageTagsUiState

    data class Loaded(
        /** Every tag in use, most used first. */
        val tags: List<TagUsage>,
        /** The [tagKey]s of the favorite tags. */
        val favoriteKeys: Set<String>,
        /**
         * Favorites no recipe carries any more, alphabetically. They stay favorites (and come
         * back to the filter bar with their next recipe) until the user unstars them.
         */
        val unusedFavorites: List<String>,
        val suggestions: TagSuggestions,
        /** Changes are being written to the recipes. */
        val applying: Boolean
    ) : ManageTagsUiState {
        val busy: Boolean get() = applying || suggestions == TagSuggestions.Loading

        fun isFavorite(tag: String): Boolean = tagKey(tag) in favoriteKeys
    }
}

@HiltViewModel
class ManageTagsViewModel @Inject constructor(
    recipeRepository: RecipeRepository,
    private val tagCleanupUseCase: TagCleanupUseCase,
    private val tagPreferences: TagPreferences,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val suggestions = MutableStateFlow<TagSuggestions>(TagSuggestions.None)
    private val applying = MutableStateFlow(false)

    private val eventChannel = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<ManageTagsUiState> = combine(
        recipeRepository.getAllRecipes().map { recipes -> tagUsage(recipes.map { it.tags }) }.flowOn(defaultDispatcher),
        tagPreferences.favoriteTags,
        suggestions,
        applying
    ) { tags, favorites, suggestions, applying ->
        val usedKeys = tags.map { it.name }.tagKeys()
        ManageTagsUiState.Loaded(
            tags = tags,
            favoriteKeys = favorites.tagKeys(),
            unusedFavorites = favorites.filter { tagKey(it) !in usedKeys }.sortedBy(::tagKey),
            suggestions = suggestions,
            applying = applying
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ManageTagsUiState.Loading)

    private val loaded: ManageTagsUiState.Loaded? get() = uiState.value as? ManageTagsUiState.Loaded

    /**
     * Stars or unstars [tag]. An unstarred tag no recipe carries disappears from the list, and
     * can only be starred again once a recipe has it.
     */
    fun setFavorite(tag: String, favorite: Boolean) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { tagPreferences.setTagFavorite(tag, favorite) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Couldn't ${if (favorite) "star" else "unstar"} a tag", e)
                eventChannel.send(UserMessage(R.string.favorite_tag_failed))
            }
        }
    }

    fun suggestCleanup() {
        if (applying.value || suggestions.value != TagSuggestions.None) return
        suggestions.value = TagSuggestions.Loading
        viewModelScope.launch {
            when (val result = tagCleanupUseCase.suggestChanges()) {
                is Outcome.Success -> {
                    val changes = result.value
                    if (changes.isEmpty()) {
                        suggestions.value = TagSuggestions.None
                        eventChannel.send(UserMessage(R.string.tag_cleanup_nothing_to_change))
                    } else {
                        suggestions.value = TagSuggestions.Review(changes, changes.map { it.key }.toSet())
                    }
                }
                is Outcome.Failure -> {
                    suggestions.value = TagSuggestions.None
                    eventChannel.send(result.error.toUserMessage())
                }
            }
        }
    }

    fun setChangeSelected(key: String, selected: Boolean) {
        suggestions.update { current ->
            if (current !is TagSuggestions.Review) return@update current
            current.copy(selected = if (selected) current.selected + key else current.selected - key)
        }
    }

    fun discardSuggestions() {
        if (!applying.value && suggestions.value is TagSuggestions.Review) suggestions.value = TagSuggestions.None
    }

    fun applySelectedChanges() {
        val review = suggestions.value as? TagSuggestions.Review ?: return
        apply(review.selectedChanges, clearSuggestions = true)
    }

    /** Renames [tag] (merging it into another tag if one has that name already). */
    fun renameTag(tag: String, newName: String) {
        if (newName.isBlank()) return
        applyManual(TagMapping(tag, newName))
    }

    fun deleteTag(tag: String) = applyManual(TagMapping(tag, null))

    private fun applyManual(mapping: TagMapping) {
        val state = loaded ?: return
        if (state.suggestions != TagSuggestions.None || applying.value) return
        val changes = planTagChanges(state.tags, listOf(mapping))
        if (changes.isEmpty()) {
            // The tag is gone (e.g. changed on another device) or the name is unchanged
            eventChannel.trySend(UserMessage(R.string.tags_update_failed))
            return
        }
        apply(changes, clearSuggestions = false)
    }

    private fun apply(changes: List<TagChange>, clearSuggestions: Boolean) {
        if (changes.isEmpty() || applying.value) return
        applying.value = true
        viewModelScope.launch {
            // The use case finishes the writes even if this scope is cancelled
            val result = tagCleanupUseCase.applyChanges(changes)
            applying.value = false
            when (result) {
                is Outcome.Success -> {
                    if (clearSuggestions) suggestions.value = TagSuggestions.None
                    eventChannel.send(UserMessage.Plural(R.plurals.tags_updated, result.value))
                }
                is Outcome.Failure -> eventChannel.send(UserMessage(R.string.tags_update_failed))
            }
        }
    }

    private companion object {
        const val TAG = "ManageTagsViewModel"
    }
}
