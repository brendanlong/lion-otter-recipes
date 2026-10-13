package com.lionotter.recipes.messages

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text to show the user, as a string resource plus format arguments, so ViewModels and workers
 * never build user-facing strings themselves (and never show exception messages). Arguments
 * that are themselves [UserMessage]s are resolved too.
 */
sealed interface UserMessage {
    data class Text(@param:StringRes val resId: Int, val args: List<Any> = emptyList()) : UserMessage

    data class Plural(
        @param:PluralsRes val resId: Int,
        val count: Int,
        val args: List<Any> = listOf(count)
    ) : UserMessage
}

fun UserMessage(@StringRes resId: Int, vararg args: Any): UserMessage = UserMessage.Text(resId, args.toList())

fun UserMessage.resolve(resources: Resources): String = when (this) {
    is UserMessage.Text -> resources.getString(resId, *resolveArgs(args, resources))
    is UserMessage.Plural -> resources.getQuantityString(resId, count, *resolveArgs(args, resources))
}

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UserMessage) it.resolve(resources) else it }.toTypedArray()
