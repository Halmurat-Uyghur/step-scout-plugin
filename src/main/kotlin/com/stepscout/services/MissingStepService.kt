package com.stepscout.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.stepscout.settings.StepScoutSettings
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder

data class MissingStep(val text: String, val fileUrl: String, val filePath: String, val lineNumber: Int)

data class FeatureScan(val missingSteps: List<MissingStep>, val featureCount: Int, val scenarioCount: Int)

/**
 * Scans `.feature` files for steps without a matching definition and gathers feature statistics.
 * All methods must be called inside a read action.
 */
@Service(Service.Level.PROJECT)
class MissingStepService(private val project: Project) {

    /**
     * Walks every non-excluded feature file once, collecting steps that no definition matches
     * together with the feature and scenario counts.
     *
     * Scenario Outline steps are checked once per Examples row with `<placeholders>` substituted,
     * and reported if any row has no matching definition. Scenario Outlines count one scenario per
     * Examples row; Backgrounds are not counted.
     */
    fun scanFeatures(): FeatureScan {
        val settings = StepScoutSettings.getInstance(project)
        val featureFiles = FilenameIndex.getAllFilesByExt(project, "feature", GlobalSearchScope.projectScope(project))
            .filterNot { settings.isExcluded(it.path) }
        val psiManager = PsiManager.getInstance(project)
        val docManager = PsiDocumentManager.getInstance(project)
        val patterns = StepSearchService.getInstance(project).getStepDefinitions().map { it.regex }

        // Many steps repeat across features; match each distinct text only once.
        val matchCache = HashMap<String, Boolean>()
        fun isDefined(text: String) = matchCache.getOrPut(text) {
            val input = CancellableCharSequence(text)
            patterns.any { it.matches(input) }
        }

        val missing = mutableListOf<MissingStep>()
        var scenarioCount = 0
        for (vf in featureFiles) {
            ProgressManager.checkCanceled()
            val psiFile = psiManager.findFile(vf) ?: continue
            val document = docManager.getDocument(psiFile)

            for (holder in PsiTreeUtil.collectElementsOfType(psiFile, GherkinStepsHolder::class.java)) {
                ProgressManager.checkCanceled()
                scenarioCount += scenarioWeight(holder)
                val rows = (holder as? GherkinScenarioOutline)?.let { exampleRows(it) }.orEmpty()

                for (step in holder.steps) {
                    val stepText = step.name.trim()
                    val candidates = if (rows.isEmpty()) listOf(stepText) else rows.map { substitute(stepText, it) }
                    if (candidates.all { isDefined(it) }) continue

                    val line = document?.getLineNumber(step.textOffset)?.plus(1) ?: 1
                    missing += MissingStep(stepText, vf.url, vf.path, line)
                }
            }
        }
        return FeatureScan(missing, featureFiles.size, scenarioCount)
    }

    private fun scenarioWeight(holder: GherkinStepsHolder): Int = when (holder) {
        is GherkinScenarioOutline -> holder.examplesBlocks.sumOf { it.table?.dataRows?.size ?: 0 }.coerceAtLeast(1)
        is GherkinScenario -> if (holder.isBackground) 0 else 1
        else -> 0
    }

    /** Returns each Examples row as a map of column header to cell value. */
    private fun exampleRows(outline: GherkinScenarioOutline): List<Map<String, String>> =
        outline.examplesBlocks.flatMap { block ->
            val table = block.table ?: return@flatMap emptyList()
            val headers = table.headerRow?.psiCells?.map { it.text.trim() } ?: return@flatMap emptyList()
            table.dataRows.map { row ->
                headers.zip(row.psiCells.map { it.text.trim() }).toMap()
            }
        }

    private fun substitute(stepText: String, row: Map<String, String>): String =
        PLACEHOLDER.replace(stepText) { match -> row[match.groupValues[1]] ?: match.value }

    /**
     * Lets a pathological user-written regex (e.g. `^(a+)+$`) be interrupted: regex matching never
     * checks for cancellation itself, so a runaway match would otherwise block write actions.
     */
    private class CancellableCharSequence(private val text: CharSequence) : CharSequence {
        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads and 0xFFF == 0) ProgressManager.checkCanceled()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CancellableCharSequence(text.subSequence(startIndex, endIndex))

        override fun toString(): String = text.toString()
    }

    companion object {
        private val PLACEHOLDER = Regex("<([^<>]+)>")

        fun getInstance(project: Project): MissingStepService = project.service()
    }
}
