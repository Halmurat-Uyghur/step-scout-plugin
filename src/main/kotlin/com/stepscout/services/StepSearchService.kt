package com.stepscout.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

data class StepDefinition(
    val regex: Regex,
    val filePath: String,
    val lineNumber: Int,
    val className: String,
    val screenName: String
)

data class StepResult(val text: String, val filePath: String, val lineNumber: Int)

class StepSearchService(
    private val project: Project,
    private val testDefinitions: List<StepDefinition>? = null
) {
    
    // Cache for expensive PSI operations
    @Volatile
    private var cachedDefinitions: List<StepDefinition>? = null
    
    // Simple cache invalidation - clear on any file change
    fun invalidateCache() {
        cachedDefinitions = null
    }

    private fun extractScreenName(text: String): String {
        val colon = text.indexOf(':')
        if (colon <= 0) return ""

        // The screen name must appear before the first space to avoid
        // misinterpreting times like "12:00" as a screen name
        val firstSpace = text.indexOf(' ')
        if (firstSpace != -1 && colon > firstSpace) return ""

        return text.substring(0, colon).trim()
    }

    // Support both modern io.cucumber and legacy cucumber.api annotation packages
    private val stepAnnotations = listOf(
        "io.cucumber.java.en.Given",
        "io.cucumber.java.en.When",
        "io.cucumber.java.en.Then",
        "io.cucumber.java.en.And",
        "io.cucumber.java.en.But",
        "cucumber.api.java.en.Given",
        "cucumber.api.java.en.When",
        "cucumber.api.java.en.Then",
        "cucumber.api.java.en.And",
        "cucumber.api.java.en.But"
    )

    /**
     * Returns all step definitions in the project along with their locations.
     */
    fun getStepDefinitions(): List<StepDefinition> {
        if (testDefinitions != null) return testDefinitions
        
        // Return cached result if available
        cachedDefinitions?.let { return it }
        
        // Check if project is disposed or indices are not ready
        if (project.isDisposed || DumbService.getInstance(project).isDumb) {
            return emptyList()
        }
        
        try {
            val scope = GlobalSearchScope.allScope(project)
            val facade = JavaPsiFacade.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val docManager = PsiDocumentManager.getInstance(project)

            val steps = mutableListOf<StepDefinition>()

        for (fqName in stepAnnotations) {
            val clazz = facade.findClass(fqName, scope) ?: continue
            val methods = AnnotatedElementsSearch.searchPsiMethods(clazz, scope).findAll()
            for (method in methods) {
                val annotation = method.getAnnotation(fqName)
                val rawValue = annotation?.findAttributeValue("value")?.text ?: ""
                val unquoted = com.intellij.openapi.util.text.StringUtil.unquoteString(rawValue)
                val value = com.intellij.openapi.util.text.StringUtil.unescapeStringCharacters(unquoted)
                val pattern = if (value.isNotBlank()) value else method.name

                val hasGroups = pattern.contains("(") && pattern.contains(")")
                val looksLikeRegex = pattern.startsWith("^") || pattern.endsWith("$") ||
                    pattern.contains("\\") || hasGroups

                val regexText = if (looksLikeRegex) {
                    var tmp = pattern
                    if (!tmp.startsWith("^")) tmp = "^$tmp"
                    if (!tmp.endsWith("$")) tmp += "$"
                    tmp
                } else {
                    // Convert cucumber expressions like {string} into wildcards and escape
                    val parts = "\\{[^}]+\\}".toRegex().split(pattern)
                    val escaped = parts.joinToString(".*") { Regex.escape(it) }
                    val completed = if (!escaped.startsWith("^")) "^$escaped" else escaped
                    if (!completed.endsWith("$")) "$completed$" else completed
                }
                val file = method.containingFile
                val vf = file.virtualFile ?: continue
                val line = docManager.getDocument(file)?.getLineNumber(method.textOffset)?.plus(1) ?: 1
                val className = method.containingClass?.qualifiedName ?: vf.nameWithoutExtension
                val screen = extractScreenName(value)
                steps += StepDefinition(
                    Regex(regexText, RegexOption.IGNORE_CASE),
                    vf.path,
                    line,
                    className,
                    screen
                )
            }
        }

        // Kotlin step definitions using cucumber-java8 En API
        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        for (vf in ktFiles) {
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            val calls = PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)
            for (call in calls) {
                val name = call.calleeExpression?.text ?: continue
                if (name !in listOf("Given", "When", "Then", "And", "But")) continue
                val arg = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression ?: continue
                val raw = arg.text
                val value = com.intellij.openapi.util.text.StringUtil.unquoteString(raw)
                val hasGroups = value.contains("(") && value.contains(")")
                val looksLikeRegex = value.startsWith("^") || value.endsWith("$") ||
                    value.contains("\\") || hasGroups
                val regexText = if (looksLikeRegex) {
                    var tmp = value
                    if (!tmp.startsWith("^")) tmp = "^$tmp"
                    if (!tmp.endsWith("$")) tmp += "$"
                    tmp
                } else {
                    val parts = "\\{[^}]+\\}".toRegex().split(value)
                    val escaped = parts.joinToString(".*") { Regex.escape(it) }
                    val completed = if (!escaped.startsWith("^")) "^$escaped" else escaped
                    if (!completed.endsWith("$")) "$completed$" else completed
                }
                val line = docManager.getDocument(ktFile)?.getLineNumber(call.textOffset)?.plus(1) ?: 1
                val className = ktFile.name.substringBeforeLast(".")
                val screen = extractScreenName(value)
                steps += StepDefinition(
                    Regex(regexText, RegexOption.IGNORE_CASE),
                    vf.path,
                    line,
                    className,
                    screen
                )
            }
            }
            
            // Cache the results for future use
            cachedDefinitions = steps
            return steps
        } catch (e: IndexNotReadyException) {
            // Indices are not ready, return empty list
            return emptyList()
        } catch (e: Exception) {
            // Handle other exceptions gracefully
            return emptyList()
        }
    }

    /**
     * Returns a list of regex patterns representing all step definitions in the project.
     */
    fun getStepPatterns(): List<Regex> = getStepDefinitions().map { it.regex }

    fun findSteps(
        query: String,
        classFilter: Set<String>? = null,
        screenFilter: String? = null
    ): List<StepResult> {
        val steps = getStepDefinitions()
        val results = steps
            .filter { classFilter == null || it.className in classFilter }
            .filter { screenFilter == null || it.screenName == screenFilter }
            .map { StepResult(displayPattern(it.regex), it.filePath, it.lineNumber) }

        if (query.isBlank()) {
            return results.sortedBy { it.text }
        }

        return results
            .map { it to matchScore(it.text, query) }
            .filter { it.second != Int.MIN_VALUE }
            .sortedWith(compareByDescending<Pair<StepResult, Int>> { it.second }.thenBy { it.first.text })
            .map { it.first }
    }

    private fun tokenize(query: String): List<String> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()

        val split = trimmed.split("\\s+".toRegex()).flatMap {
            it.split("(?<=[a-z])(?=[A-Z])".toRegex())
        }.filter { it.isNotBlank() }

        if (split.size == 1 && split[0].startsWith("user") && split[0].length > 4) {
            return listOf("user", split[0].substring(4))
        }

        return split
    }

    private fun tokensPresent(text: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val words = text.lowercase().split("[^a-z0-9]+".toRegex())
        return tokens.all { token -> words.any { it.contains(token) } }
    }

    private fun matchesQuery(text: String, query: String): Boolean {
        return matchScore(text, query) != Int.MIN_VALUE
    }

    private fun matchScore(text: String, query: String): Int {
        if (query.isBlank()) return 0

        val tokens = tokenize(query)
        if (!tokensPresent(text, tokens)) return Int.MIN_VALUE

        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        val directIndex = lowerText.indexOf(lowerQuery)
        if (directIndex != -1) return 300 - directIndex

        val cleanText = lowerText.replace("[^a-z0-9]".toRegex(), "")
        val cleanQuery = lowerQuery.replace("[^a-z0-9]".toRegex(), "")

        val cleanIndex = cleanText.indexOf(cleanQuery)
        if (cleanIndex != -1) return 200 - cleanIndex

        val subseqGap = subsequenceGap(cleanText, cleanQuery)
        if (subseqGap != null && subseqGap <= cleanQuery.length) {
            return 100 - subseqGap
        }

        return Int.MIN_VALUE
    }

    private fun subsequenceGap(text: String, query: String): Int? {
        var i = 0
        var first = -1
        for ((index, c) in text.withIndex()) {
            if (i < query.length && c == query[i]) {
                if (first == -1) first = index
                i++
                if (i == query.length) {
                    val window = index - first + 1
                    return window - query.length
                }
            }
        }
        return null
    }

    fun getStepClasses(): Map<String, Int> {
        return getStepDefinitions().groupingBy { it.className }.eachCount()
    }

    fun getScreenNames(): Map<String, Int> {
        return getStepDefinitions()
            .filter { it.screenName.isNotBlank() }
            .groupingBy { it.screenName }
            .eachCount()
    }

    private fun displayPattern(regex: Regex): String {
        var pattern = regex.pattern

        // remove anchors that were added when constructing the Regex
        pattern = pattern.removePrefix("^").removeSuffix("$")

        // Step definitions originating from cucumber expressions are escaped
        // using `Pattern.quote` which inserts `\Q` and `\E` around each literal
        // segment and joins them with `.*`. To present a user-friendly text we
        // strip these markers and replace the wildcards with a `{string}` placeholder.
        if (pattern.contains("\\Q") || pattern.contains("\\E")) {
            pattern = pattern.replace("\\Q", "").replace("\\E", "")
            pattern = pattern.replace(".*", "{string}")
        }

        return pattern
    }

    /**
     * Returns true if a step definition exists that matches the given [stepText].
     * This does a simple conversion of cucumber expressions like `{string}` into
     * wildcard patterns so parameterised steps can be matched.
     */
    fun hasStepDefinition(stepText: String): Boolean {
        val patterns = getStepPatterns()
        return patterns.any { it.matches(stepText.trim()) }
    }

    /**
     * Returns the total number of step definitions in the project.
     */
    fun countStepDefinitions(): Int = getStepPatterns().size
}
