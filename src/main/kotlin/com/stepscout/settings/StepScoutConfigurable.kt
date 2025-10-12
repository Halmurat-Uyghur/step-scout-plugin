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
        if (component == null) {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.add(JLabel("Exclude paths (one per line):"))
            panel.add(JBScrollPane(textarea))
            component = panel
        }
        return component!!
    }

    override fun isModified(): Boolean {
        val settings = StepScoutSettings.getInstance(project)
        val joined = settings.excludePaths.joinToString("\n")
        return textarea.text.trimEnd() != joined
    }

    override fun apply() {
        val settings = StepScoutSettings.getInstance(project)
        val lines = textarea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        settings.excludePaths = lines.toMutableList()
    }

    override fun reset() {
        val settings = StepScoutSettings.getInstance(project)
        textarea.text = settings.excludePaths.joinToString("\n")
    }

    override fun getDisplayName(): String = "StepScout"
}
