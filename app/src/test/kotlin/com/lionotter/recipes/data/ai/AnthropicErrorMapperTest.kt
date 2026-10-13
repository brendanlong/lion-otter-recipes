package com.lionotter.recipes.data.ai

import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicErrorMapperTest {
    private val headers = Headers.builder().build()
    private fun body(message: String) = JsonValue.from(mapOf("type" to "error", "error" to mapOf("message" to message)))

    @Test
    fun `HTTP errors map to what went wrong, with the status`() {
        val cases = listOf(
            UnauthorizedException.builder().headers(headers).body(body("bad key")).build() to
                RecipeError.Ai(AiFailure.INVALID_API_KEY, 401),
            PermissionDeniedException.builder().headers(headers).body(body("no")).build() to
                RecipeError.Ai(AiFailure.PERMISSION_DENIED, 403),
            NotFoundException.builder().headers(headers).body(body("no model")).build() to
                RecipeError.Ai(AiFailure.MODEL_UNAVAILABLE, 404),
            RateLimitException.builder().headers(headers).body(body("slow down")).build() to
                RecipeError.Ai(AiFailure.RATE_LIMITED, 429),
            BadRequestException.builder().headers(headers).body(body("Your credit balance is too low")).build() to
                RecipeError.Ai(AiFailure.OUT_OF_CREDITS, 400),
            BadRequestException.builder().headers(headers).body(body("Schema is too complex")).build() to
                RecipeError.Ai(AiFailure.REQUEST_REJECTED, 400),
            InternalServerException.builder().statusCode(500).headers(headers).body(body("oops")).build() to
                RecipeError.Ai(AiFailure.OVERLOADED, 500),
            UnexpectedStatusCodeException.builder().statusCode(529).headers(headers).body(body("overloaded")).build() to
                RecipeError.Ai(AiFailure.OVERLOADED, 529),
            UnexpectedStatusCodeException.builder().statusCode(418).headers(headers).body(body("teapot")).build() to
                RecipeError.Ai(AiFailure.HTTP_ERROR, 418),
        )
        cases.forEach { (exception, expected) ->
            assertEquals(exception.javaClass.simpleName, expected, AnthropicErrorMapper.toRecipeError(exception))
        }
    }

    @Test
    fun `network errors are transient, other failures are unknown`() {
        val io = AnthropicErrorMapper.toRecipeError(java.io.IOException("boom"))
        assertEquals(RecipeError.Ai(AiFailure.NETWORK), io)
        assertTrue(io.retryable)
        assertEquals(RecipeError.Ai(AiFailure.NETWORK), AnthropicErrorMapper.toRecipeError(AnthropicIoException("boom")))
        val unknown = AnthropicErrorMapper.toRecipeError(IllegalStateException())
        assertEquals(RecipeError.Ai(AiFailure.UNKNOWN), unknown)
        assertFalse(unknown.retryable)
    }
}
