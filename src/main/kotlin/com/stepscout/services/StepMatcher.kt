package com.stepscout.services

data class StepDefinition(
    /** The pattern as written in source, used for display. */
    val expression: String,
    val regex: Regex,
    val fileUrl: String,
    val filePath: String,
    val lineNumber: Int,
    val className: String,
    val screenName: String
)

data class StepResult(val text: String, val fileUrl: String, val filePath: String, val lineNumber: Int)

/**
 * Pure filtering and fuzzy ranking of step definitions for the search box.
 */
object StepMatcher {

    private val WHITESPACE = Regex("\\s+")
    private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z])(?=[A-Z])")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    const val NO_MATCH = Int.MIN_VALUE

    fun findSteps(
        definitions: List<StepDefinition>,
        query: String,
        classFilter: Set<String>? = null,
        screenFilter: String? = null
    ): List<StepResult> {
        val filtered = definitions.asSequence()
            .filter { classFilter == null || it.className in classFilter }
            .filter { screenFilter == null || it.screenName == screenFilter }
            .map { StepResult(it.expression, it.fileUrl, it.filePath, it.lineNumber) }

        if (query.isBlank()) {
            return filtered.sortedBy { it.text }.toList()
        }

        val tokens = tokenize(query)
        return filtered
            .map { it to matchScore(it.text, query, tokens) }
            .filter { it.second != NO_MATCH }
            .sortedWith(compareByDescending<Pair<StepResult, Int>> { it.second }.thenBy { it.first.text })
            .map { it.first }
            .toList()
    }

    /**
     * Returns the screen prefix of a step pattern, i.e. the text before a colon in the first word
     * (`"Login: I tap submit"` → `"Login"`). Times like `"at 12:00"` are not treated as screens.
     */
    fun extractScreenName(pattern: String): String {
        // Ignore regex delimiters so "^Login: ..." and "Login: ..." share a screen.
        val text = pattern.removePrefix("^").removePrefix("/")
        val colon = text.indexOf(':')
        if (colon <= 0) return ""
        val firstSpace = text.indexOf(' ')
        if (firstSpace != -1 && colon > firstSpace) return ""
        return text.substring(0, colon).trim()
    }

    internal fun tokenize(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Tokens are compared against the alphanumeric words of each step, so drop punctuation
        // (e.g. "log-in", "{int}", "Login:") the same way.
        val split = trimmed.split(WHITESPACE)
            .flatMap { it.split(CAMEL_CASE_BOUNDARY) }
            .flatMap { it.lowercase().split(NON_ALPHANUMERIC) }
            .filter { it.isNotEmpty() }

        if (split.size == 1 && split[0].startsWith("user") && split[0].length > 4) {
            return listOf("user", split[0].substring(4))
        }
        return split
    }

    internal fun matchScore(text: String, query: String, tokens: List<String> = tokenize(query)): Int {
        if (query.isBlank()) return 0

        val lowerText = text.lowercase()
        val words = lowerText.split(NON_ALPHANUMERIC)
        if (!tokens.all { token -> words.any { it.contains(token) } }) return NO_MATCH

        val lowerQuery = query.lowercase()
        val directIndex = lowerText.indexOf(lowerQuery)
        if (directIndex != -1) return 300 - directIndex

        val cleanText = lowerText.replace(NON_ALPHANUMERIC, "")
        val cleanQuery = lowerQuery.replace(NON_ALPHANUMERIC, "")
        val cleanIndex = cleanText.indexOf(cleanQuery)
        if (cleanIndex != -1) return 200 - cleanIndex

        val gap = subsequenceGap(cleanText, cleanQuery)
        if (gap != null && gap <= cleanQuery.length) return 100 - gap

        return NO_MATCH
    }

    private fun subsequenceGap(text: String, query: String): Int? {
        if (query.isEmpty()) return null
        var i = 0
        var first = -1
        for ((index, c) in text.withIndex()) {
            if (c == query[i]) {
                if (first == -1) first = index
                i++
                if (i == query.length) return (index - first + 1) - query.length
            }
        }
        return null
    }
}
