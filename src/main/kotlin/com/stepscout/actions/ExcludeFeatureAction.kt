package com.stepscout.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.IconLoader
import com.stepscout.settings.StepScoutSettings
import com.stepscout.settings.StepScoutSettingsListener

class ExcludeFeatureAction : AnAction(
    "Exclude from StepScout",
    "Add this file to StepScout's excluded paths",
    IconLoader.getIcon("/icons/pluginIconSmall.svg", ExcludeFeatureAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        val settings = StepScoutSettings.getInstance(project)
        val projectPath = project.basePath ?: ""
        var added = false

        for (file in files) {
            if (!file.name.endsWith(".feature")) continue
            // Use path relative to project root for cleaner exclusion entries
            val relativePath = if (projectPath.isNotEmpty() && file.path.startsWith(projectPath)) {
                file.path.removePrefix(projectPath).removePrefix("/")
            } else {
                file.name
            }
            if (relativePath !in settings.excludePaths) {
                settings.excludePaths.add(relativePath)
                added = true
            }
        }

        if (added) {
            project.messageBus.syncPublisher(StepScoutSettingsListener.TOPIC).settingsChanged()
        }
    }

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = files != null &&
            files.any { it.name.endsWith(".feature") }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
