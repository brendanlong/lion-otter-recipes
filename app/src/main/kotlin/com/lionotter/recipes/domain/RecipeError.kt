package com.lionotter.recipes.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Why an import, edit, regeneration or archive operation failed. Carries no user-facing text
 * (except [NotARecipe.reason], which is the AI's own explanation): the UI maps each case to a
 * localized message, and details such as exception messages only go to the log.
 *
 * Errors are persisted (WorkManager output, the import queue) through [encode] / [decode], so
 * the serial names and enum constants must never be renamed.
 */
@Serializable
sealed interface RecipeError {
    /** Whether trying again later might succeed (network trouble, rate limits, overload). */
    val retryable: Boolean get() = false

    @Serializable
    @SerialName("no_api_key")
    data object NoApiKey : RecipeError

    /** Regenerating needs the recipe's source URL. */
    @Serializable
    @SerialName("no_source_url")
    data object NoSourceUrl : RecipeError

    /** The recipe being edited or regenerated no longer exists. */
    @Serializable
    @SerialName("recipe_not_found")
    data object RecipeNotFound : RecipeError

    @Serializable
    @SerialName("limit_reached")
    data class LimitReached(val count: Int, val limit: Int) : RecipeError

    /** The recipe's web page couldn't be fetched. */
    @Serializable
    @SerialName("page_fetch")
    data class PageFetch(val reason: PageFetchFailure, val httpStatus: Int? = null) : RecipeError {
        override val retryable: Boolean get() = reason.transient
    }

    /** The AI request failed, or its response couldn't be used. */
    @Serializable
    @SerialName("ai")
    data class Ai(val reason: AiFailure, val httpStatus: Int? = null) : RecipeError {
        override val retryable: Boolean get() = reason.transient
    }

    /** The AI read the content and decided it isn't a recipe; [reason] is its explanation. */
    @Serializable
    @SerialName("not_a_recipe")
    data class NotARecipe(val reason: String) : RecipeError

    /** A recipe archive (.lorecipes or Paprika export) couldn't be read or written. */
    @Serializable
    @SerialName("archive")
    data class Archive(val reason: ArchiveFailure) : RecipeError

    /** The work was stopped before it finished (e.g. its input was lost) and must be started again. */
    @Serializable
    @SerialName("interrupted")
    data object Interrupted : RecipeError

    /** A failed import whose inputs are gone, so it has to be started again rather than retried. */
    @Serializable
    @SerialName("cannot_retry")
    data object CannotRetry : RecipeError

    /** A bug or an unexpected failure; the details are logged. */
    @Serializable
    @SerialName("unexpected")
    data object Unexpected : RecipeError

    /** A failure recorded by an app version before errors were typed: its (English) message. */
    @Serializable
    @SerialName("legacy")
    data class Legacy(val message: String) : RecipeError

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        /** Longest AI explanation kept, so an encoded error stays well within WorkManager's Data limit. */
        private const val MAX_TEXT_LENGTH = 1000

        fun encode(error: RecipeError): String {
            val bounded = when (error) {
                is NotARecipe -> error.copy(reason = error.reason.take(MAX_TEXT_LENGTH))
                is Legacy -> error.copy(message = error.message.take(MAX_TEXT_LENGTH))
                else -> error
            }
            return json.encodeToString<RecipeError>(bounded)
        }

        /** Null if [encoded] isn't an encoded error (e.g. a message written by an older version). */
        fun decode(encoded: String): RecipeError? = try {
            json.decodeFromString<RecipeError>(encoded)
        } catch (expected: IllegalArgumentException) {
            // Not an encoded error, e.g. a message written by an older version
            null
        }
    }
}

@Serializable
enum class PageFetchFailure(val transient: Boolean = false) {
    NETWORK(transient = true),
    TOO_LARGE,
    /** A bot check (e.g. Cloudflare) blocked the request. */
    BOT_CHECK,
    /** HTTP 401/403. */
    BLOCKED,
    /** HTTP 404/410. */
    NOT_FOUND,
    RATE_LIMITED(transient = true),
    SERVER_ERROR(transient = true),
    /** Any other HTTP error status. */
    HTTP_ERROR,
    UNKNOWN
}

@Serializable
enum class AiFailure(val transient: Boolean = false) {
    INVALID_API_KEY,
    PERMISSION_DENIED,
    MODEL_UNAVAILABLE,
    RATE_LIMITED(transient = true),
    OUT_OF_CREDITS,
    REQUEST_REJECTED,
    OVERLOADED(transient = true),
    NETWORK(transient = true),
    /** Any other HTTP error status. */
    HTTP_ERROR,
    /** The model declined to process the content. */
    REFUSED,
    /** The model ran out of output tokens. */
    TRUNCATED,
    EMPTY_RESPONSE,
    UNREADABLE_RESPONSE,
    UNKNOWN
}

@Serializable
enum class ArchiveFailure {
    TOO_LARGE,
    UNREADABLE,
    NOT_AN_EXPORT,
    NO_RECIPES,
    /** The archive has recipes, but none of them could be read. */
    NO_READABLE_RECIPES,
    /** The file staged for the import is gone. */
    MISSING,
    NOTHING_TO_EXPORT,
    WRITE_FAILED
}

/** A short English description for logs and the import debugging screen (never shown as an error message). */
fun RecipeError.logDescription(): String = when (this) {
    is RecipeError.NotARecipe -> "Not a recipe: $reason"
    is RecipeError.Ai -> "AI request failed: $reason${httpStatus?.let { " (HTTP $it)" } ?: ""}"
    is RecipeError.PageFetch -> "Page fetch failed: $reason${httpStatus?.let { " (HTTP $it)" } ?: ""}"
    else -> toString()
}

/** An exception that knows which [RecipeError] it represents. */
interface RecipeErrorSource {
    val recipeError: RecipeError
}

/** The [RecipeError] for a failure, or [RecipeError.Unexpected] if it isn't a known kind. */
fun Throwable?.toRecipeError(): RecipeError = (this as? RecipeErrorSource)?.recipeError ?: RecipeError.Unexpected

/** Fails with a known [RecipeError]. */
class RecipeErrorException(
    override val recipeError: RecipeError,
    cause: Throwable? = null
) : Exception(recipeError.toString(), cause), RecipeErrorSource
