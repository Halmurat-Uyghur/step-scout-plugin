package com.stepscout.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates the StepScout tool window. Anchor, icon and activation are declared in plugin.xml.
 */
class StepScoutToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StepScoutPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false).apply {
            isCloseable = false
        }
        toolWindow.contentManager.addContent(content)
        panel.start()
    }
}
