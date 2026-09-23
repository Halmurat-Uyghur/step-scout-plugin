package com.stepscout.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.stepscout.settings.StepScoutSettings
import com.stepscout.settings.StepScoutSettingsListener

class ExcludeFeatureAction : AnAction(
    "Exclude from StepScout",
    "Add this file to StepScout's excluded paths",
    IconLoader.getIcon("/icons/pluginIconSmall.svg", ExcludeFeatureAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = featureFiles(e)
        val settings = StepScoutSettings.getInstance(project)
        val projectPath = project.basePath.orEmpty()

        // Store paths relative to the project root for cleaner exclusion entries.
        val newPaths = files
            .map { file ->
                if (projectPath.isNotEmpty() && file.path.startsWith("$projectPath/")) {
                    file.path.removePrefix("$projectPath/")
                } else {
                    file.path
                }
            }
            .filter { it !in settings.excludePaths }
            .distinct()
        if (newPaths.isEmpty()) return

        // Assign a new list so the settings modification tracker invalidates cached scans.
        settings.excludePaths = (settings.excludePaths + newPaths).toMutableList()
        project.messageBus.syncPublisher(StepScoutSettingsListener.TOPIC).settingsChanged()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = featureFiles(e).isNotEmpty()
    }

    /** Editor contexts may only provide a single file, so fall back to VIRTUAL_FILE. */
    private fun featureFiles(e: AnActionEvent): List<VirtualFile> {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return files.filter { it.extension.equals("feature", ignoreCase = true) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
