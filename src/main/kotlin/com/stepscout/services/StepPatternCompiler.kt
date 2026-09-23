package com.stepscout.services

import java.util.regex.PatternSyntaxException

/**
 * Turns the text of a step definition (a Cucumber expression or a regular expression)
 * into a [Regex] that matches the step text the same way Cucumber would.
 *
 * This is pure logic with no IntelliJ dependencies so it can be unit tested directly.
 */
object StepPatternCompiler {

    private const val INT = "-?\\d+"
    private const val FLOAT = "-?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)(?:[eE][-+]?\\d+)?"
    private const val WORD = "[^\\s]+"
    private const val STRING = "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"
    private const val ANYTHING = ".*"

    private val parameterRegexes = mapOf(
        "int" to INT, "long" to INT, "short" to INT, "byte" to INT, "biginteger" to INT,
        "float" to FLOAT, "double" to FLOAT, "bigdecimal" to FLOAT,
        "word" to WORD,
        "string" to STRING,
        "" to ANYTHING,
    )

    /**
     * Compiles [pattern] into an anchored regex, or returns `null` when the pattern is invalid.
     *
     * @param forceRegex treat the pattern as a regular expression regardless of anchors. Legacy
     *   `cucumber.api` annotations (Cucumber-JVM < 3) only understood regular expressions.
     */
    fun compile(pattern: String, forceRegex: Boolean = false): Regex? {
        val regexText = if (forceRegex || isRegularExpression(pattern)) {
            anchor(pattern)
        } else {
            "^" + expressionToRegex(pattern) + "$"
        }
        return try {
            Regex(regexText)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** Cucumber treats a pattern as a regular expression when it is anchored with `^` or `$`. */
    fun isRegularExpression(pattern: String): Boolean =
        pattern.startsWith("^") || pattern.endsWith("$")

    private fun anchor(pattern: String): String {
        var text = pattern
        if (!text.startsWith("^")) text = "^$text"
        if (!text.endsWith("$")) text = "$text$"
        return text
    }

    /**
     * Converts a Cucumber expression to a regular expression body (without anchors).
     *
     * Supports parameters (`{int}`, `{string}`, custom `{type}`), optional text (`(s)`),
     * alternation (`cat/dog`) and backslash escapes (`\(`, `\{`, `\/`, `\\`).
     */
    fun expressionToRegex(expression: String): String {
        val out = StringBuilder()
        // Alternation is scoped to whitespace-delimited chunks, so convert chunk by chunk.
        var i = 0
        val chunk = StringBuilder()
        while (i < expression.length) {
            val c = expression[i]
            if (c == '\\' && i + 1 < expression.length) {
                chunk.append(c).append(expression[i + 1])
                i += 2
                continue
            }
            if (c.isWhitespace()) {
                out.append(convertChunk(chunk.toString()))
                chunk.clear()
                out.append(Regex.escape(c.toString()))
            } else {
                chunk.append(c)
            }
            i++
        }
        out.append(convertChunk(chunk.toString()))
        return out.toString()
    }

    /** Converts a chunk that contains no whitespace, handling `a/b` alternation. */
    private fun convertChunk(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val alternatives = splitUnescaped(chunk, '/')
        if (alternatives.size == 1) return convertTerm(chunk)
        return alternatives.joinToString("|", prefix = "(?:", postfix = ")") { convertTerm(it) }
    }

    /** Converts a term containing literal text, `{parameters}` and `(optional)` text. */
    private fun convertTerm(term: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                out.append(Regex.escape(literal.toString()))
                literal.clear()
            }
        }

        var i = 0
        while (i < term.length) {
            val c = term[i]
            when {
                c == '\\' && i + 1 < term.length -> {
                    literal.append(term[i + 1])
                    i += 2
                }
                c == '{' -> {
                    val end = term.indexOf('}', i + 1)
                    if (end == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        val name = term.substring(i + 1, end).trim()
                        out.append("(").append(parameterRegexes[name] ?: ANYTHING).append(")")
                        i = end + 1
                    }
                }
                c == '(' -> {
                    val end = findUnescaped(term, ')', i + 1)
                    if (end == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        val optional = unescape(term.substring(i + 1, end))
                        out.append("(?:").append(Regex.escape(optional)).append(")?")
                        i = end + 1
                    }
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral()
        return out.toString()
    }

    private fun splitUnescaped(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                current.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == separator) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        parts += current.toString()
        return parts
    }

    private fun findUnescaped(text: String, target: Char, from: Int): Int {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                target -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                out.append(text[i + 1])
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}
