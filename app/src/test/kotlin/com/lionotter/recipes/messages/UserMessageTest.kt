package com.lionotter.recipes.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every error resolves to a complete, localized message (format arguments match the strings). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserMessageTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun assertComplete(message: UserMessage) {
        val text = message.resolve(resources)
        assertTrue("Empty message for $message", text.isNotBlank())
        assertFalse("Unformatted placeholder in \"$text\"", Regex("%\\d*\\$?[sd]").containsMatchIn(text))
    }

    @Test
    fun `every recipe error has a message`() {
        RecipeErrorTest.allErrors().forEach { assertComplete(it.toUserMessage()) }
    }

    @Test
    fun `every data failure has a message`() {
        DataOp.entries.forEach { assertComplete(DataFailure(it, "id").toUserMessage()) }
    }

    @Test
    fun `messages include their details`() {
        assertEquals(
            "The site blocked the request (403).",
            RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403).toUserMessage().resolve(resources)
        )
        assertEquals(
            "Couldn't read a recipe: It's a blog post.",
            RecipeError.NotARecipe("It's a blog post.").toUserMessage().resolve(resources)
        )
        assertEquals("Old message", RecipeError.Legacy("Old message").toUserMessage().resolve(resources))
    }

    @Test
    fun `plural and nested arguments resolve`() {
        assertEquals(
            "Exported 1 recipe",
            UserMessage.Plural(R.plurals.exported_recipes, 1).resolve(resources)
        )
        assertEquals(
            "Couldn't read a recipe: Couldn't load recipes.",
            UserMessage(R.string.error_not_a_recipe, UserMessage(R.string.meal_plan_recipes_load_failed)).resolve(resources)
        )
    }
}
