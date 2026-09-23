package com.stepscout.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.stepscout.settings.StepScoutSettings
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Discovers Cucumber step definitions in Java (annotations) and Kotlin (cucumber-java8 lambdas).
 *
 * Results are cached until PSI changes, so repeated searches and refreshes are cheap.
 * All methods must be called inside a read action.
 */
@Service(Service.Level.PROJECT)
class StepSearchService(private val project: Project) {

    private val definitionsCache: CachedValue<List<StepDefinition>> =
        CachedValuesManager.getManager(project).createCachedValue {
            CachedValueProvider.Result.create(
                computeStepDefinitions(),
                PsiModificationTracker.MODIFICATION_COUNT,
                StepScoutSettings.getInstance(project).modificationTracker
            )
        }

    /**
     * Returns all step definitions in the project and its libraries. Returns an empty list while
     * indexes are not ready.
     */
    fun getStepDefinitions(): List<StepDefinition> {
        if (project.isDisposed || DumbService.isDumb(project)) return emptyList()
        return definitionsCache.value
    }

    fun findSteps(query: String, classFilter: Set<String>? = null, screenFilter: String? = null): List<StepResult> =
        StepMatcher.findSteps(getStepDefinitions(), query, classFilter, screenFilter)

    fun getStepClasses(): Map<String, Int> = getStepDefinitions().groupingBy { it.className }.eachCount()

    fun getScreenNames(): Map<String, Int> = getStepDefinitions()
        .filter { it.screenName.isNotBlank() }
        .groupingBy { it.screenName }
        .eachCount()

    fun countStepDefinitions(): Int = getStepDefinitions().size

    private fun computeStepDefinitions(): List<StepDefinition> {
        val settings = StepScoutSettings.getInstance(project)
        val steps = mutableListOf<StepDefinition>()
        collectAnnotatedDefinitions(steps)
        collectKotlinLambdaDefinitions(steps)
        return steps.filterNot { settings.isExcluded(it.filePath) }
    }

    private fun collectAnnotatedDefinitions(into: MutableList<StepDefinition>) {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        for ((annotationFqn, legacy) in findStepAnnotationClasses(facade, scope)) {
            val annotationClass = facade.findClass(annotationFqn, scope) ?: continue
            AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope).forEach { method ->
                ProgressManager.checkCanceled()
                // A method can carry several (repeated) step annotations.
                method.modifierList.annotations
                    .filter { it.qualifiedName == annotationFqn }
                    .forEach { annotation -> addAnnotatedDefinition(method, annotation, legacy, into) }
                true
            }
        }
    }

    /**
     * Returns the fully-qualified names of all step annotations (every Gherkin language), mapped to
     * whether they belong to the legacy `cucumber.api` package, which only supports regular expressions.
     */
    private fun findStepAnnotationClasses(facade: JavaPsiFacade, scope: GlobalSearchScope): Map<String, Boolean> {
        val result = linkedMapOf<String, Boolean>()
        for ((metaAnnotation, legacy) in STEP_META_ANNOTATIONS) {
            val meta = facade.findClass(metaAnnotation, scope) ?: continue
            AnnotatedElementsSearch.searchPsiClasses(meta, scope).forEach { cls ->
                cls.qualifiedName?.let { result[it] = legacy }
                true
            }
        }
        for (fqn in ENGLISH_ANNOTATIONS) {
            result.putIfAbsent(fqn, fqn.startsWith(LEGACY_PACKAGE))
        }
        return result
    }

    private fun addAnnotatedDefinition(
        method: PsiMethod,
        annotation: PsiAnnotation,
        legacy: Boolean,
        into: MutableList<StepDefinition>
    ) {
        // Evaluate constants and concatenations such as @Given(PREFIX + "text").
        val value = annotation.findAttributeValue("value") ?: return
        val pattern = JavaPsiFacade.getInstance(project).constantEvaluationHelper
            .computeConstantExpression(value) as? String
        if (pattern.isNullOrBlank()) return
        val className = method.containingClass?.qualifiedName
        addDefinition(pattern, legacy, method.navigationElement, className, into)
    }

    private fun collectKotlinLambdaDefinitions(into: MutableList<StepDefinition>) {
        val scope = GlobalSearchScope.projectScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)
        // File -> whether it uses the legacy, regex-only cucumber.api.java8 API.
        val candidates = linkedMapOf<KtFile, Boolean>()
        // Use the word index to only parse Kotlin files that mention a step keyword.
        for (keyword in KOTLIN_STEP_FUNCTIONS) {
            searchHelper.processAllFilesWithWord(keyword, scope, { file: PsiFile ->
                ProgressManager.checkCanceled()
                if (file is KtFile && file !in candidates) {
                    java8Api(file)?.let { legacy -> candidates[file] = legacy }
                }
                true
            }, true)
        }

        for ((ktFile, legacy) in candidates) {
            for (call in PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)) {
                ProgressManager.checkCanceled()
                val name = call.calleeExpression?.text ?: continue
                if (name !in KOTLIN_STEP_FUNCTIONS) continue
                val argument = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
                    ?: continue
                val pattern = literalValue(argument) ?: continue
                val className = ktFile.packageFqName.asString()
                    .let { pkg -> if (pkg.isEmpty()) "" else "$pkg." } + ktFile.name.substringBeforeLast('.')
                addDefinition(pattern, legacy = legacy, element = call, className = className, into = into)
            }
        }
    }

    /**
     * Returns `false` if the file imports the cucumber-java8 API, `true` if it imports the legacy
     * `cucumber.api.java8` API, or `null` if it imports neither (e.g. Kotest's `Given` blocks).
     */
    private fun java8Api(file: KtFile): Boolean? {
        val imports = file.importDirectives.mapNotNull { it.importedFqName?.asString() }
        return when {
            imports.any { it.startsWith("io.cucumber.java8") } -> false
            imports.any { it.startsWith("cucumber.api.java8") } -> true
            else -> null
        }
    }

    /** Returns the value of a Kotlin string literal, or `null` if it interpolates values. */
    private fun literalValue(template: KtStringTemplateExpression): String? {
        val builder = StringBuilder()
        for (entry in template.entries) {
            when (entry) {
                is KtLiteralStringTemplateEntry -> builder.append(entry.text)
                is KtEscapeStringTemplateEntry -> builder.append(entry.unescapedValue)
                else -> return null
            }
        }
        return builder.toString()
    }

    private fun addDefinition(
        pattern: String,
        legacy: Boolean,
        element: PsiElement,
        className: String?,
        into: MutableList<StepDefinition>
    ) {
        try {
            val regex = StepPatternCompiler.compile(pattern, forceRegex = legacy)
            if (regex == null) {
                LOG.info("Skipping step definition with invalid pattern: $pattern")
                return
            }
            val file = element.containingFile ?: return
            val vf = file.virtualFile ?: file.originalFile.virtualFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            val offset = element.textOffset
            val line = if (document != null && offset in 0..document.textLength) document.getLineNumber(offset) + 1 else 1
            into += StepDefinition(
                expression = pattern,
                regex = regex,
                fileUrl = vf.url,
                filePath = vf.path,
                lineNumber = line,
                className = className ?: vf.nameWithoutExtension,
                screenName = StepMatcher.extractScreenName(pattern)
            )
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            LOG.warn("Failed to read step definition: $pattern", e)
        }
    }

    companion object {
        private val LOG = logger<StepSearchService>()

        private const val LEGACY_PACKAGE = "cucumber.api."

        private val STEP_META_ANNOTATIONS = mapOf(
            "io.cucumber.java.StepDefinitionAnnotation" to false,
            "cucumber.runtime.java.StepDefAnnotation" to true,
        )

        private val ENGLISH_ANNOTATIONS = listOf("Given", "When", "Then", "And", "But").flatMap {
            listOf("io.cucumber.java.en.$it", "cucumber.api.java.en.$it")
        }

        private val KOTLIN_STEP_FUNCTIONS = setOf("Given", "When", "Then", "And", "But")

        fun getInstance(project: Project): StepSearchService = project.service()
    }
}
