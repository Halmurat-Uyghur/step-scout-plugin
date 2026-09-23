package com.stepscout.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class StepScoutConfigurable(private val project: Project) : Configurable {
    private var component: JPanel? = null
    private val textarea = JTextArea(5, 40)

    override fun createComponent(): JComponent {
        return component ?: JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Exclude paths (one per line):"))
            add(JBScrollPane(textarea))
            component = this
        }
    }

    private fun enteredPaths(): List<String> = textarea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    override fun isModified(): Boolean = enteredPaths() != StepScoutSettings.getInstance(project).excludePaths

    override fun apply() {
        StepScoutSettings.getInstance(project).excludePaths = enteredPaths().toMutableList()
        project.messageBus.syncPublisher(StepScoutSettingsListener.TOPIC).settingsChanged()
    }

    override fun reset() {
        textarea.text = StepScoutSettings.getInstance(project).excludePaths.joinToString("\n")
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getDisplayName(): String = "StepScout"
}
