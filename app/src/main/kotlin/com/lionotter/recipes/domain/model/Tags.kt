package com.lionotter.recipes.domain.model

/** Identifies a tag regardless of case and surrounding whitespace (" Dessert" == "dessert"). */
fun tagKey(tag: String): String = tag.trim().lowercase()

/** True if this recipe carries [tag], compared by [tagKey]. */
fun Recipe.hasTag(tag: String): Boolean {
    val key = tagKey(tag)
    return tags.any { tagKey(it) == key }
}

/** True if [query] is blank or appears (ignoring case) in the recipe's name or one of its tags. */
fun Recipe.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) }
