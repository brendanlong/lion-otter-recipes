/**
 * Kotlin port of pluralize.js (https://github.com/plurals/pluralize)
 * Original library by Blake Embrey, MIT License
 *
 * Provides pluralization and singularization of English words.
 */
package com.lionotter.recipes.util

object Pluralizer {
    private val pluralRules = mutableListOf<Pair<Regex, String>>()
    private val singularRules = mutableListOf<Pair<Regex, String>>()
    private val uncountables = mutableSetOf<String>()
    private val irregularPlurals = mutableMapOf<String, String>()
    private val irregularSingles = mutableMapOf<String, String>()

    init {
        loadIrregularRules()
        loadPluralRules()
        loadSingularRules()
        loadUncountableRules()
    }

    /**
     * Pluralize or singularize a word based on the passed in count.
     *
     * @param word The word to pluralize
     * @param count How many of the word exist
     * @param inclusive Whether to prefix with the number (e.g. "3 ducks")
     * @return The pluralized or singularized word
     */
    fun pluralize(word: String, count: Int = 2, inclusive: Boolean = false): String {
        val result = if (count == 1) singular(word) else plural(word)
        return if (inclusive) "$count $result" else result
    }

    /**
     * Pluralize a word.
     */
    fun plural(word: String): String {
        return replaceWord(word, irregularSingles, irregularPlurals, pluralRules)
    }

    /**
     * Singularize a word.
     */
    fun singular(word: String): String {
        return replaceWord(word, irregularPlurals, irregularSingles, singularRules)
    }

    /**
     * Check if a word is plural.
     */
    fun isPlural(word: String): Boolean {
        val token = word.lowercase()
        if (irregularPlurals.containsKey(token)) return true
        if (irregularSingles.containsKey(token)) return false
        return checkWord(token, pluralRules)
    }

    /**
     * Check if a word is singular.
     */
    fun isSingular(word: String): Boolean {
        val token = word.lowercase()
        if (irregularSingles.containsKey(token)) return true
        if (irregularPlurals.containsKey(token)) return false
        return checkWord(token, singularRules)
    }

    private fun replaceWord(
        word: String,
        replaceMap: Map<String, String>,
        keepMap: Map<String, String>,
        rules: List<Pair<Regex, String>>
    ): String {
        val token = word.lowercase()

        // Check for uncountable words
        if (uncountables.contains(token)) {
            return word
        }

        // Check against the keep object map
        if (keepMap.containsKey(token)) {
            return restoreCase(word, token)
        }

        // Check against the replacement map for a direct word replacement
        if (replaceMap.containsKey(token)) {
            return restoreCase(word, replaceMap[token]!!)
        }

        // Run all the rules against the word
        return sanitizeWord(token, word, rules)
    }

    private fun checkWord(token: String, rules: List<Pair<Regex, String>>): Boolean {
        if (uncountables.contains(token)) return true
        return sanitizeWord(token, token, rules) == token
    }

    private fun sanitizeWord(
        token: String,
        word: String,
        rules: List<Pair<Regex, String>>
    ): String {
        if (token.isEmpty() || uncountables.contains(token)) {
            return word
        }

        // Iterate over the rules in reverse and use the first one to match
        for (i in rules.indices.reversed()) {
            val (regex, replacement) = rules[i]
            if (regex.containsMatchIn(word)) {
                return applyRule(word, regex, replacement)
            }
        }

        return word
    }

    private fun applyRule(word: String, regex: Regex, replacement: String): String {
        return regex.replace(word) { match ->
            val result = interpolate(replacement, match)
            if (match.value.isEmpty()) {
                restoreCase(word.getOrNull(match.range.first - 1)?.toString() ?: "", result)
            } else {
                restoreCase(match.value, result)
            }
        }
    }

    private fun interpolate(str: String, match: MatchResult): String {
        return str.replace(Regex("""\$(\d{1,2})""")) { m ->
            val index = m.groupValues[1].toInt()
            match.groupValues.getOrElse(index) { "" }
        }
    }

    private fun restoreCase(word: String, token: String): String {
        if (word == token) return token
        if (word.isEmpty()) return token
        if (word == word.lowercase()) return token.lowercase()
        if (word == word.uppercase()) return token.uppercase()
        if (word[0].isUpperCase()) {
            return token.replaceFirstChar { it.uppercase() }
        }
        return token.lowercase()
    }

    private fun addPluralRule(rule: Regex, replacement: String) {
        pluralRules.add(rule to replacement)
    }

    private fun addSingularRule(rule: Regex, replacement: String) {
        singularRules.add(rule to replacement)
    }

    private fun addUncountableRule(word: String) {
        uncountables.add(word.lowercase())
    }

    private fun addUncountableRule(regex: Regex) {
        // For regex uncountables, add as rules that map to themselves
        addPluralRule(regex, "$0")
        addSingularRule(regex, "$0")
    }

    private fun addIrregularRule(single: String, plural: String) {
        val s = single.lowercase()
        val p = plural.lowercase()
        irregularSingles[s] = p
        irregularPlurals[p] = s
    }

    private fun loadIrregularRules() {
        listOf(
            // Pronouns
            "I" to "we",
            "me" to "us",
            "he" to "they",
            "she" to "they",
            "them" to "them",
            "myself" to "ourselves",
            "yourself" to "yourselves",
            "itself" to "themselves",
            "herself" to "themselves",
            "himself" to "themselves",
            "themself" to "themselves",
            "is" to "are",
            "was" to "were",
            "has" to "have",
            "this" to "these",
            "that" to "those",
            "my" to "our",
            "its" to "their",
            "his" to "their",
            "her" to "their",
            // Words ending with a consonant and `o`
            "echo" to "echoes",
            "dingo" to "dingoes",
            "volcano" to "volcanoes",
            "tornado" to "tornadoes",
            "torpedo" to "torpedoes",
            // Ends with `us`
            "genus" to "genera",
            "viscus" to "viscera",
            // Ends with `ma`
            "stigma" to "stigmata",
            "stoma" to "stomata",
            "dogma" to "dogmata",
            "lemma" to "lemmata",
            "schema" to "schemata",
            "anathema" to "anathemata",
            // Other irregular rules
            "ox" to "oxen",
            "axe" to "axes",
            "die" to "dice",
            "yes" to "yeses",
            "foot" to "feet",
            "eave" to "eaves",
            "goose" to "geese",
            "tooth" to "teeth",
            "quiz" to "quizzes",
            "human" to "humans",
            "proof" to "proofs",
            "carve" to "carves",
            "valve" to "valves",
            "looey" to "looies",
            "thief" to "thieves",
            "groove" to "grooves",
            "pickaxe" to "pickaxes",
            "passerby" to "passersby",
            "canvas" to "canvases"
        ).forEach { (single, plural) -> addIrregularRule(single, plural) }
    }

    private fun loadPluralRules() {
        listOf(
            Regex("""s?$""", RegexOption.IGNORE_CASE) to "s",
            Regex("""[^\u0000-\u007F]$""", RegexOption.IGNORE_CASE) to "$0",
            Regex("""([^aeiou]ese)$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(ax|test)is$""", RegexOption.IGNORE_CASE) to "$1es",
            Regex("""(alias|[^aou]us|t[lm]as|gas|ris)$""", RegexOption.IGNORE_CASE) to "$1es",
            Regex("""(e[mn]u)s?$""", RegexOption.IGNORE_CASE) to "$1s",
            Regex("""([^l]ias|[aeiou]las|[ejzr]as|[iu]am)$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(alumn|syllab|vir|radi|nucle|fung|cact|stimul|termin|bacill|foc|uter|loc|strat)(?:us|i)$""", RegexOption.IGNORE_CASE) to "$1i",
            Regex("""(alumn|alg|vertebr)(?:a|ae)$""", RegexOption.IGNORE_CASE) to "$1ae",
            Regex("""(seraph|cherub)(?:im)?$""", RegexOption.IGNORE_CASE) to "$1im",
            Regex("""(her|at|gr)o$""", RegexOption.IGNORE_CASE) to "$1oes",
            Regex("""(agend|addend|millenni|dat|extrem|bacteri|desiderat|strat|candelabr|errat|ov|symposi|curricul|automat|quor)(?:a|um)$""", RegexOption.IGNORE_CASE) to "$1a",
            Regex("""(apheli|hyperbat|periheli|asyndet|noumen|phenomen|criteri|organ|prolegomen|hedr|automat)(?:a|on)$""", RegexOption.IGNORE_CASE) to "$1a",
            Regex("""sis$""", RegexOption.IGNORE_CASE) to "ses",
            Regex("""(?:(kni|wi|li)fe|(ar|l|ea|eo|oa|hoo)f)$""", RegexOption.IGNORE_CASE) to "$1$2ves",
            Regex("""([^aeiouy]|qu)y$""", RegexOption.IGNORE_CASE) to "$1ies",
            Regex("""([^ch][ieo][ln])ey$""", RegexOption.IGNORE_CASE) to "$1ies",
            Regex("""(x|ch|ss|sh|zz)$""", RegexOption.IGNORE_CASE) to "$1es",
            Regex("""(matr|cod|mur|sil|vert|ind|append)(?:ix|ex)$""", RegexOption.IGNORE_CASE) to "$1ices",
            Regex("""\b((?:tit)?m|l)(?:ice|ouse)$""", RegexOption.IGNORE_CASE) to "$1ice",
            Regex("""(pe)(?:rson|ople)$""", RegexOption.IGNORE_CASE) to "$1ople",
            Regex("""(child)(?:ren)?$""", RegexOption.IGNORE_CASE) to "$1ren",
            Regex("""eaux$""", RegexOption.IGNORE_CASE) to "$0",
            Regex("""m[ae]n$""", RegexOption.IGNORE_CASE) to "men",
            Regex("""^thou$""", RegexOption.IGNORE_CASE) to "you"
        ).forEach { (rule, replacement) -> addPluralRule(rule, replacement) }
    }

    private fun loadSingularRules() {
        listOf(
            Regex("""s$""", RegexOption.IGNORE_CASE) to "",
            Regex("""(ss)$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(wi|kni|(?:after|half|high|low|mid|non|night|[^\w]|^)li)ves$""", RegexOption.IGNORE_CASE) to "$1fe",
            Regex("""(ar|(?:wo|[ae])l|[eo][ao])ves$""", RegexOption.IGNORE_CASE) to "$1f",
            Regex("""ies$""", RegexOption.IGNORE_CASE) to "y",
            Regex("""(dg|ss|ois|lk|ok|wn|mb|th|ch|ec|oal|is|ck|ix|sser|ts|wb)ies$""", RegexOption.IGNORE_CASE) to "$1ie",
            Regex("""\b(l|(?:neck|cross|hog|aun)?t|coll|faer|food|gen|goon|group|hipp|junk|vegg|(?:pork)?p|charl|calor|cut)ies$""", RegexOption.IGNORE_CASE) to "$1ie",
            Regex("""\b(mon|smil)ies$""", RegexOption.IGNORE_CASE) to "$1ey",
            Regex("""\b((?:tit)?m|l)ice$""", RegexOption.IGNORE_CASE) to "$1ouse",
            Regex("""(seraph|cherub)im$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(x|ch|ss|sh|zz|tto|go|cho|alias|[^aou]us|t[lm]as|gas|(?:her|at|gr)o|[aeiou]ris)(?:es)?$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(analy|diagno|parenthe|progno|synop|the|empha|cri|ne)(?:sis|ses)$""", RegexOption.IGNORE_CASE) to "$1sis",
            Regex("""(movie|twelve|abuse|e[mn]u)s$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(test)(?:is|es)$""", RegexOption.IGNORE_CASE) to "$1is",
            Regex("""(alumn|syllab|vir|radi|nucle|fung|cact|stimul|termin|bacill|foc|uter|loc|strat)(?:us|i)$""", RegexOption.IGNORE_CASE) to "$1us",
            Regex("""(agend|addend|millenni|dat|extrem|bacteri|desiderat|strat|candelabr|errat|ov|symposi|curricul|quor)a$""", RegexOption.IGNORE_CASE) to "$1um",
            Regex("""(apheli|hyperbat|periheli|asyndet|noumen|phenomen|criteri|organ|prolegomen|hedr|automat)a$""", RegexOption.IGNORE_CASE) to "$1on",
            Regex("""(alumn|alg|vertebr)ae$""", RegexOption.IGNORE_CASE) to "$1a",
            Regex("""(cod|mur|sil|vert|ind)ices$""", RegexOption.IGNORE_CASE) to "$1ex",
            Regex("""(matr|append)ices$""", RegexOption.IGNORE_CASE) to "$1ix",
            Regex("""(pe)(rson|ople)$""", RegexOption.IGNORE_CASE) to "$1rson",
            Regex("""(child)ren$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""(eau)x?$""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""men$""", RegexOption.IGNORE_CASE) to "man"
        ).forEach { (rule, replacement) -> addSingularRule(rule, replacement) }
    }

    private fun loadUncountableRules() {
        // Singular words with no plurals
        listOf(
            "adulthood", "advice", "agenda", "aid", "aircraft", "alcohol", "ammo",
            "analytics", "anime", "athletics", "audio", "bison", "blood", "bream",
            "buffalo", "butter", "carp", "cash", "chassis", "chess", "clothing",
            "cod", "commerce", "cooperation", "corps", "debris", "diabetes",
            "digestion", "elk", "energy", "equipment", "excretion", "expertise",
            "firmware", "flounder", "fun", "gallows", "garbage", "graffiti",
            "hardware", "headquarters", "health", "herpes", "highjinks", "homework",
            "housework", "information", "jeans", "justice", "kudos", "labour",
            "literature", "machinery", "mackerel", "mail", "media", "mews", "moose",
            "music", "mud", "manga", "news", "only", "personnel", "pike", "plankton",
            "pliers", "police", "pollution", "premises", "rain", "research", "rice",
            "salmon", "scissors", "series", "sewage", "shambles", "shrimp", "software",
            "staff", "swine", "tennis", "traffic", "transportation", "trout", "tuna",
            "wealth", "welfare", "whiting", "wildebeest", "wildlife", "you"
        ).forEach { addUncountableRule(it) }

        // Regex uncountables
        listOf(
            Regex("""pok[eé]mon$""", RegexOption.IGNORE_CASE),
            Regex("""[^aeiou]ese$""", RegexOption.IGNORE_CASE),  // "chinese", "japanese"
            Regex("""deer$""", RegexOption.IGNORE_CASE),         // "deer", "reindeer"
            Regex("""fish$""", RegexOption.IGNORE_CASE),         // "fish", "blowfish", "angelfish"
            Regex("""measles$""", RegexOption.IGNORE_CASE),
            Regex("""o[iu]s$""", RegexOption.IGNORE_CASE),       // "carnivorous"
            Regex("""pox$""", RegexOption.IGNORE_CASE),          // "chickpox", "smallpox"
            Regex("""sheep$""", RegexOption.IGNORE_CASE)
        ).forEach { addUncountableRule(it) }
    }
}

/**
 * Extension function to pluralize a string.
 */
fun String.pluralize(count: Int = 2, inclusive: Boolean = false): String {
    return Pluralizer.pluralize(this, count, inclusive)
}

/**
 * Extension function to singularize a string.
 */
fun String.singularize(): String {
    return Pluralizer.singular(this)
}
