package com.stepscout.services

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.stepscout.settings.StepScoutSettings
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import org.jetbrains.plugins.cucumber.psi.GherkinTable

data class MissingStep(val text: String, val filePath: String, val lineNumber: Int)

class MissingStepService(private val project: Project, private val searchService: StepSearchService) {
    private fun isExcluded(path: String): Boolean {
        val settings = StepScoutSettings.getInstance(project)
        return settings.excludePaths.any { ex ->
            val normalized = ex.replace('\\', '/')
            path.contains(normalized)
        }
    }
    /**
     * Returns the number of steps referenced in feature files but missing step definitions.
     *
     * This walks through all `*.feature` files in the project, extracts each Gherkin step and
     * checks whether a step definition exists using [StepSearchService]. The count is intentionally
     * simplistic and may not handle regular expressions or placeholders perfectly, but it provides
     * a reasonable indication of missing steps.
     */
    fun findMissingSteps(): List<MissingStep> {
        val scope = GlobalSearchScope.projectScope(project)
        val featureFiles = FilenameIndex.getAllFilesByExt(project, "feature", scope)
            .filterNot { isExcluded(it.path) }
        val psiManager = PsiManager.getInstance(project)
        val docManager = PsiDocumentManager.getInstance(project)

        val patterns = searchService.getStepPatterns()
        val missing = mutableListOf<MissingStep>()
        for (vf in featureFiles) {
            val psiFile = psiManager.findFile(vf) ?: continue
            val document = docManager.getDocument(psiFile)
            val steps = PsiTreeUtil.collectElementsOfType(psiFile, GherkinStep::class.java)
            for (step in steps) {
                val stepText = step.name.trim()
                if (patterns.none { it.matches(stepText) }) {
                    val line = document?.getLineNumber(step.textOffset)?.plus(1) ?: 1
                    missing += MissingStep(stepText, vf.path, line)
                }
            }
        }
        return missing
    }

    fun countMissingSteps(): Int = findMissingSteps().size

    /**
     * Returns the number of `.feature` files in the project.
     */
    fun countFeatureFiles(): Int {
        val scope = GlobalSearchScope.projectScope(project)
        return FilenameIndex.getAllFilesByExt(project, "feature", scope)
            .count { !isExcluded(it.path) }
    }

    /**
     * Returns the number of scenarios (including scenario outlines) across all feature files.
     */
    fun countScenarios(): Int {
        val scope = GlobalSearchScope.projectScope(project)
        val files = FilenameIndex.getAllFilesByExt(project, "feature", scope)
            .filterNot { isExcluded(it.path) }
        val psiManager = PsiManager.getInstance(project)
        var count = 0
        for (vf in files) {
            val psiFile = psiManager.findFile(vf) ?: continue

            // plain "Scenario" entries
            count += PsiTreeUtil.collectElementsOfType(psiFile, GherkinScenario::class.java).size

            // for outlines, count each example row; fallback to 1 if none
            val outlines = PsiTreeUtil.collectElementsOfType(psiFile, GherkinScenarioOutline::class.java)
            for (outline in outlines) {
                var rows = 0
                for (block in outline.examplesBlocks) {
                    val table: GherkinTable? = block.table
                    rows += table?.dataRows?.size ?: 0
                }
                count += if (rows > 0) rows else 1
            }
        }
        return count
    }
}
