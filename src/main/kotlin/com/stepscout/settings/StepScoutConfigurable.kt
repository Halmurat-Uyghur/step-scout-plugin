package com.stepscout.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class StepScoutConfigurable(private val project: Project) : Configurable {
    private var component: JPanel? = null
    private val textarea = JTextArea(5, 40)

    override fun createComponent(): JComponent {
        if (component == null) {
            val label = JLabel("Exclude paths (one per line):").apply {
                border = JBUI.Borders.emptyBottom(4)
            }
            val panel = JPanel(BorderLayout()).apply {
                add(label, BorderLayout.NORTH)
                add(JBScrollPane(textarea), BorderLayout.CENTER)
            }
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
        project.messageBus.syncPublisher(StepScoutSettingsListener.TOPIC).settingsChanged()
    }

    override fun reset() {
        val settings = StepScoutSettings.getInstance(project)
        textarea.text = settings.excludePaths.joinToString("\n")
    }

    override fun getDisplayName(): String = "StepScout"
}
