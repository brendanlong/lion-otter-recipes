package com.lionotter.recipes.util

/** Where [ErrorLog] writes log lines. The app installs one backed by `android.util.Log`. */
fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Logging for code that must not depend on Android (the domain layer), plus crash reporting:
 * [e] also forwards its exception to crash reporting as a non-fatal.
 *
 * Only the exception is recorded, not the message, since messages often include recipe names
 * or URLs. [sink] defaults to stderr (unit tests); the Application installs the Android one.
 * [recorder] is installed by the Application in release builds; tests and other builds leave it
 * unset.
 */
object ErrorLog {
    @Volatile
    var sink: LogSink = StderrSink

    @Volatile
    var recorder: ((Throwable) -> Unit)? = null

    fun d(tag: String, message: String) = sink.log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = sink.log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = sink.log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        sink.log(LogLevel.ERROR, tag, message, throwable)
        if (throwable == null) return
        try {
            recorder?.invoke(withoutUserContent(throwable))
        } catch (e: Exception) {
            w(tag, "Failed to record error for crash reporting", e)
        }
    }

    /**
     * kotlinx.serialization errors quote the input around the failure ("JSON input: ..."),
     * which can be recipe text. Returns [throwable] unchanged unless something in its cause
     * chain has such a message, in which case the chain is copied with those parts removed.
     */
    internal fun withoutUserContent(throwable: Throwable): Throwable {
        if (generateSequence(throwable) { it.cause }.take(MAX_CAUSE_DEPTH).none(::hasJsonInput)) {
            return throwable
        }
        return scrubbedCopy(throwable, 0)
    }

    private fun scrubbedCopy(throwable: Throwable, depth: Int): Throwable {
        val cause = throwable.cause?.takeIf { depth < MAX_CAUSE_DEPTH }?.let { scrubbedCopy(it, depth + 1) }
        val message = throwable.message?.substringBefore(JSON_INPUT_MARKER)?.trimEnd()
        return ScrubbedException("${throwable.javaClass.name}: $message", cause).also {
            it.stackTrace = throwable.stackTrace
        }
    }

    private fun hasJsonInput(throwable: Throwable) = throwable.message?.contains(JSON_INPUT_MARKER) == true

    private const val JSON_INPUT_MARKER = "JSON input:"
    private const val MAX_CAUSE_DEPTH = 10

    class ScrubbedException(message: String, cause: Throwable?) : Exception(message, cause)

    private object StderrSink : LogSink {
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            System.err.println("${level.name.first()}/$tag: $message")
            throwable?.printStackTrace()
        }
    }
}
