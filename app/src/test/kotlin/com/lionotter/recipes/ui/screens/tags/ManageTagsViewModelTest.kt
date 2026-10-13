package com.lionotter.recipes.ui.screens.tags

import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.domain.usecase.TagCleanupUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.FakeTagOrganizer
import com.lionotter.recipes.testutil.FakeTagPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ManageTagsViewModelTest {
    private val repository = FakeRecipeRepository(
        recipe("cake", "Dessert", "recipe"),
        recipe("pie", "Desserts", "Dessert"),
        recipe("soup", "Dinner")
    )
    private val organizer = FakeTagOrganizer()
    private val settings = FakeAiSettings()
    private val tagPreferences = FakeTagPreferences()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recipe(id: String, vararg tags: String) = Recipe(
        id = id,
        name = id,
        tags = tags.toList(),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    private class Subject(val viewModel: ManageTagsViewModel, val events: List<UserMessage>) {
        val state: ManageTagsUiState.Loaded get() = viewModel.uiState.value as ManageTagsUiState.Loaded
    }

    private fun TestScope.subject(): Subject {
        val viewModel = ManageTagsViewModel(
            repository,
            TagCleanupUseCase(repository, organizer, settings, tagPreferences),
            tagPreferences,
            UnconfinedTestDispatcher(testScheduler)
        )
        val events = mutableListOf<UserMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        return Subject(viewModel, events)
    }

    @Test
    fun `lists every tag with its recipe count, following the recipes`() = runTest {
        val subject = subject()
        assertEquals(
            listOf(TagUsage("Dessert", 2), TagUsage("Desserts", 1), TagUsage("Dinner", 1), TagUsage("recipe", 1)),
            subject.state.tags
        )

        repository.deleteRecipe("cake")

        assertEquals(listOf(TagUsage("Dessert", 1), TagUsage("Desserts", 1), TagUsage("Dinner", 1)), subject.state.tags)
    }

    @Test
    fun `suggestions are reviewed with everything selected, and only the selected ones are applied`() = runTest {
        organizer.result = Outcome.Success(listOf(TagMapping("Desserts", "Dessert"), TagMapping("recipe", null)))
        val subject = subject()

        subject.viewModel.suggestCleanup()

        val review = subject.state.suggestions as TagSuggestions.Review
        assertEquals(
            listOf(
                TagChange("Desserts", "Dessert", 1, TagChange.Kind.MERGE),
                TagChange("recipe", null, 1, TagChange.Kind.DELETE)
            ),
            review.changes
        )
        assertEquals(setOf("desserts", "recipe"), review.selected)

        subject.viewModel.setChangeSelected("recipe", false)
        subject.viewModel.applySelectedChanges()

        assertEquals(listOf("pie" to listOf("Dessert")), repository.tagUpdates)
        assertEquals(listOf("Dessert", "recipe"), repository.recipes.value.getValue("cake").tags)
        assertEquals(TagSuggestions.None, subject.state.suggestions)
        assertEquals(listOf<UserMessage>(UserMessage.Plural(R.plurals.tags_updated, 1)), subject.events)
    }

    @Test
    fun `discarding drops the suggestions without writing`() = runTest {
        organizer.result = Outcome.Success(listOf(TagMapping("recipe", null)))
        val subject = subject()
        subject.viewModel.suggestCleanup()

        subject.viewModel.discardSuggestions()

        assertEquals(TagSuggestions.None, subject.state.suggestions)
        assertTrue(repository.tagUpdates.isEmpty())
    }

    @Test
    fun `no suggestions and failures are reported as messages`() = runTest {
        val subject = subject()
        subject.viewModel.suggestCleanup()
        organizer.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED))
        subject.viewModel.suggestCleanup()
        settings.apiKeyFlow.value = null
        subject.viewModel.suggestCleanup()

        assertEquals(TagSuggestions.None, subject.state.suggestions)
        assertEquals(
            listOf(
                UserMessage(R.string.tag_cleanup_nothing_to_change),
                RecipeError.Ai(AiFailure.OVERLOADED).toUserMessage(),
                RecipeError.NoApiKey.toUserMessage()
            ),
            subject.events
        )
    }

    @Test
    fun `shows loading while the AI works and ignores a second request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.beforeTagsRead = { gate.await() }
        val subject = subject()

        subject.viewModel.suggestCleanup()
        subject.viewModel.suggestCleanup()
        assertEquals(TagSuggestions.Loading, subject.state.suggestions)
        assertTrue(subject.state.busy)

        gate.complete(Unit)
        assertEquals(1, organizer.requests.size)
    }

    @Test
    fun `renaming to an existing tag merges, and deleting removes the tag`() = runTest {
        val subject = subject()

        subject.viewModel.renameTag("Desserts", " dessert ")
        subject.viewModel.deleteTag("recipe")

        assertEquals(listOf("Dessert"), repository.recipes.value.getValue("cake").tags)
        assertEquals(listOf("Dessert"), repository.recipes.value.getValue("pie").tags)
        assertEquals(
            listOf<UserMessage>(UserMessage.Plural(R.plurals.tags_updated, 1), UserMessage.Plural(R.plurals.tags_updated, 1)),
            subject.events
        )
    }

    @Test
    fun `a failed apply is reported and keeps the review`() = runTest {
        organizer.result = Outcome.Success(listOf(TagMapping("recipe", null)))
        val subject = subject()
        subject.viewModel.suggestCleanup()
        repository.tagsError = java.io.IOException("offline")

        subject.viewModel.applySelectedChanges()

        assertTrue(subject.state.suggestions is TagSuggestions.Review)
        assertTrue(!subject.state.applying)
        assertEquals(listOf(UserMessage(R.string.tags_update_failed)), subject.events)
    }

    @Test
    fun `a manual change of a tag that is gone is reported`() = runTest {
        val subject = subject()

        subject.viewModel.renameTag("Vanished", "Other")

        assertTrue(repository.tagUpdates.isEmpty())
        assertEquals(listOf(UserMessage(R.string.tags_update_failed)), subject.events)
    }

    @Test
    fun `starring and unstarring a tag updates the favorites`() = runTest {
        val subject = subject()

        subject.viewModel.setFavorite("Dinner", true)
        subject.viewModel.setFavorite("dessert", true)

        assertEquals(setOf("Dinner", "dessert"), tagPreferences.favoriteTagsFlow.value)
        assertTrue(subject.state.isFavorite("Dessert"))
        assertTrue(subject.state.isFavorite("Dinner"))
        assertFalse(subject.state.isFavorite("Desserts"))

        subject.viewModel.setFavorite("Dinner", false)

        assertEquals(setOf("dessert"), tagPreferences.favoriteTagsFlow.value)
        assertFalse(subject.state.isFavorite("Dinner"))
        assertTrue(subject.events.isEmpty())
    }

    @Test
    fun `a favorite whose recipes are gone stays listed until it is unstarred`() = runTest {
        tagPreferences.favoriteTagsFlow.value = setOf("Dinner", "Old")
        val subject = subject()
        assertEquals(listOf("Old"), subject.state.unusedFavorites)

        repository.deleteRecipe("soup")

        assertEquals(listOf("Dinner", "Old"), subject.state.unusedFavorites)
        assertFalse(subject.state.tags.any { it.name == "Dinner" })
        assertTrue(subject.state.isFavorite("Dinner"))

        subject.viewModel.setFavorite("Old", false)

        assertEquals(listOf("Dinner"), subject.state.unusedFavorites)
        assertEquals(setOf("Dinner"), tagPreferences.favoriteTagsFlow.value)
    }

    @Test
    fun `renaming a favorite keeps it a favorite`() = runTest {
        tagPreferences.favoriteTagsFlow.value = setOf("Dinner")
        val subject = subject()

        subject.viewModel.renameTag("Dinner", "Supper")

        assertEquals(setOf("Supper"), tagPreferences.favoriteTagsFlow.value)
        assertTrue(subject.state.isFavorite("Supper"))
        assertTrue(subject.state.unusedFavorites.isEmpty())
    }

    @Test
    fun `a failed favorite change is reported`() = runTest {
        tagPreferences.writeError = IOException("disk full")
        val subject = subject()

        subject.viewModel.setFavorite("Dinner", true)

        assertEquals(listOf(UserMessage(R.string.favorite_tag_failed)), subject.events)
        assertFalse(subject.state.isFavorite("Dinner"))
    }
}
