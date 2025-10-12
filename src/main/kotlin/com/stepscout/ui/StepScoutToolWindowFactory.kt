package com.stepscout.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.application.ReadAction
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.stepscout.services.StepSearchService
import com.stepscout.services.StepResult
import com.stepscout.services.MissingStepService
import com.stepscout.services.MissingStep
import com.intellij.icons.AllIcons
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFileManager
import javax.swing.DefaultListModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JComboBox
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

private data class Stats(
    val missing: List<MissingStep>,
    val stepCount: Int,
    val featureCount: Int,
    val scenarioCount: Int,
    val classData: Map<String, Int>,
    val screenData: Map<String, Int>
)

/**
 * Modern ToolWindowFactory implementation optimized for IntelliJ's new UI.
 * Implements DumbAware for better performance during indexing.
 * Configuration is handled in plugin.xml with new UI optimizations.
 *
 * Note: The @Suppress annotations below are necessary because the Kotlin compiler generates
 * bridge methods for ToolWindowFactory's deprecated/experimental interface methods with default
 * implementations, even though we don't explicitly override them. These methods appear in the
 * compiled bytecode and trigger IntelliJ inspections. The warnings are unavoidable until
 * JetBrains completes the API migration. Our implementation follows best practices by using
 * declarative configuration in plugin.xml (anchor, icon, doNotActivateOnStart attributes).
 */
@Suppress(
    "OVERRIDE_DEPRECATION",           // For isApplicable(Project) and isDoNotActivateOnStart
    "UnstableApiUsage"                // For getAnchor(), getIcon(), and manage() experimental methods
)
class StepScoutToolWindowFactory : ToolWindowFactory, DumbAware {
    
    // Load the default plugin icon
    private val defaultIcon = IconLoader.getIcon("/icons/pluginIconSmall.svg", StepScoutToolWindowFactory::class.java)
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val searchService = StepSearchService(project)
        val missingService = MissingStepService(project, searchService)

        // base icon is configured in plugin.xml
        val missingLabel = JLabel("Missing Steps")
        val statsLabel = JLabel("")
        val classCountLabel = JLabel("")
        val classDropdown = JComboBox<String>().apply {
            prototypeDisplayValue = "All Classes (999)"
        }
        val screenDropdown = JComboBox<String>().apply {
            prototypeDisplayValue = "All Screens (999)"
        }
        val clearButton = JButton("Clear All")
        var fullClassCounts: Map<String, Int> = emptyMap()
        var classCounts: Map<String, Int> = emptyMap()
        var displayToFull: Map<String, Set<String>> = emptyMap()
        var classKeys: List<String> = emptyList()
        var selectedClasses: Set<String>? = null
        var selectedDisplay: String? = null
        var screenCounts: Map<String, Int> = emptyMap()
        var screenKeys: List<String> = emptyList()
        var selectedScreen: String? = null

        val missingListModel = DefaultListModel<String>()
        val missingList = JBList(missingListModel)
        var missingSteps: List<MissingStep> = emptyList()

        // refresh logic must be defined before the button references it
        fun refresh() {
            DumbService.getInstance(project).runWhenSmart {
                ApplicationManager.getApplication().executeOnPooledThread {
                    // Check if project is disposed before starting
                    if (project.isDisposed) return@executeOnPooledThread
                    
                    val computedData = ReadAction.compute<Stats, RuntimeException> {
                        if (project.isDisposed) return@compute Stats(emptyList(), 0, 0, 0, emptyMap(), emptyMap())
                        
                        try {
                            val missing = missingService.findMissingSteps()
                            val stepCount = searchService.countStepDefinitions()
                            val featureCount = missingService.countFeatureFiles()
                            val scenarioCount = missingService.countScenarios()
                            val classData = searchService.getStepClasses()
                            val screenData = searchService.getScreenNames()
                            
                            Stats(missing, stepCount, featureCount, scenarioCount, classData, screenData)
                        } catch (e: Exception) {
                            // Return empty data on any error
                            Stats(emptyList(), 0, 0, 0, emptyMap(), emptyMap())
                        }
                    }
                
                    ApplicationManager.getApplication().invokeLater {
                        // Check if project is disposed before updating UI
                        if (project.isDisposed) return@invokeLater
                        
                        try {
                            val (steps, stepCountResult, featureCountResult, scenarioCountResult, classData, screenData) = computedData

                            fullClassCounts = classData
                    val counts = mutableMapOf<String, Int>()
                    val mapping = mutableMapOf<String, MutableSet<String>>()
                    classData.forEach { (full, count) ->
                        val simple = full.substringAfterLast('.')
                        counts[simple] = (counts[simple] ?: 0) + count
                        mapping.getOrPut(simple) { mutableSetOf() }.add(full)
                    }
                    classCounts = counts
                    displayToFull = mapping
                    classKeys = classCounts.keys.sorted()

                    val model = DefaultComboBoxModel<String>()
                    model.addElement("All Classes")
                    classKeys.forEach { name ->
                        model.addElement("$name (${classCounts[name]})")
                    }
                    classDropdown.model = model
                    selectedClasses = null
                    selectedDisplay = null
                    classDropdown.selectedIndex = 0

                    screenCounts = screenData
                    screenKeys = screenCounts.keys.sorted()

                    val screenModel = DefaultComboBoxModel<String>()
                    screenModel.addElement("All Screens")
                    screenKeys.forEach { name ->
                        screenModel.addElement("$name (${screenCounts[name]})")
                    }
                    screenDropdown.model = screenModel
                    selectedScreen = null
                    screenDropdown.selectedIndex = 0
                    
                    missingSteps = steps
                    missingListModel.clear()
                    steps.forEach {
                        val fileName = java.nio.file.Paths.get(it.filePath).fileName.toString()
                        missingListModel.addElement("${it.text} - $fileName:${it.lineNumber}")
                    }
                    missingLabel.text = "Missing ${steps.size} Steps"
                    statsLabel.text = "<html>Total Scenarios: $scenarioCountResult<br>Steps: $stepCountResult<br>Features: $featureCountResult</html>"
                    
                    // Update tool window icon based on missing steps
                    // Note: Icon is also managed by StepScoutIconService for automatic updates
                    if (steps.isNotEmpty()) {
                        toolWindow.setIcon(AllIcons.General.Error)
                    } else {
                        toolWindow.setIcon(defaultIcon)
                    }
                    
                    classCountLabel.text = "Steps: $stepCountResult"
                        } catch (e: Exception) {
                            // Handle UI update errors gracefully - log but don't crash
                            // Could add logging here if needed
                        }
                    }
                }
            }
        }

        val refreshButton = JButton("Refresh").apply {
            addActionListener { refresh() }
        }

        missingList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = missingList.locationToIndex(e.point)
                    if (idx >= 0 && idx < missingSteps.size) {
                        val step = missingSteps[idx]
                        val vf = VirtualFileManager.getInstance().findFileByUrl("file://${step.filePath}")
                        if (vf != null) {
                            OpenFileDescriptor(project, vf, step.lineNumber - 1, 0).navigate(true)
                        }
                    }
                }
            }
        })

        // Initial refresh - run immediately and also when smart mode starts
        refresh()
        DumbService.getInstance(project).runWhenSmart {
            refresh() // Ensure we refresh once indexing is complete
        }

        val listModel = DefaultListModel<String>()
        val resultList = JBList(listModel)
        var stepResults: List<StepResult> = emptyList()

        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Search step name"
        }

        fun updateResults() {
            val query = searchField.text
            val classFilter = selectedClasses
            val screenFilter = selectedScreen
            
            DumbService.getInstance(project).runWhenSmart {
                ApplicationManager.getApplication().executeOnPooledThread {
                    // Check if project is disposed before starting
                    if (project.isDisposed) return@executeOnPooledThread
                    
                    val searchData = ReadAction.compute<Pair<List<StepResult>, Int>, RuntimeException> {
                        if (project.isDisposed) return@compute Pair(emptyList(), 0)
                        
                        try {
                            val results = searchService.findSteps(query, classFilter, screenFilter)
                            val total = if (query.isBlank()) {
                                results.size // If no query, results is already all items
                            } else {
                                searchService.findSteps("", classFilter, screenFilter).size
                            }
                            Pair(results, total)
                        } catch (e: Exception) {
                            Pair(emptyList(), 0)
                        }
                    }
                    
                    ApplicationManager.getApplication().invokeLater {
                        // Check if project is disposed before updating UI
                        if (project.isDisposed) return@invokeLater
                        
                        try {
                            val (results, total) = searchData
                            stepResults = results
                            listModel.clear()
                            results.forEach { listModel.addElement(it.text) }
                            val labelParts = mutableListOf<String>()
                            if (selectedDisplay != null) labelParts.add(selectedDisplay!!)
                            if (selectedScreen != null) labelParts.add(selectedScreen!!)
                            val prefix = if (labelParts.isEmpty()) "Steps" else labelParts.joinToString(" / ")
                            val baseLabel = "$prefix: $total"
                            classCountLabel.text = if (query.isBlank()) {
                                baseLabel
                            } else {
                                "Results: ${results.size} (of $total)"
                            }
                        } catch (e: Exception) {
                            // Handle search UI update errors gracefully
                        }
                    }
                }
            }
        }

        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateResults()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateResults()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateResults()
        })

        classDropdown.addActionListener {
            val idx = classDropdown.selectedIndex
            if (idx <= 0) {
                selectedClasses = null
                selectedDisplay = null
            } else {
                val name = classKeys[idx - 1]
                selectedClasses = displayToFull[name]
                selectedDisplay = name
            }
            updateResults()
        }

        screenDropdown.addActionListener {
            val idx = screenDropdown.selectedIndex
            selectedScreen = if (idx <= 0) null else screenKeys[idx - 1]
            updateResults()
        }

        clearButton.addActionListener {
            searchField.text = ""
            classDropdown.selectedIndex = 0
            screenDropdown.selectedIndex = 0
            selectedClasses = null
            selectedDisplay = null
            selectedScreen = null
            updateResults()
        }

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = resultList.locationToIndex(e.point)
                    if (idx >= 0 && idx < stepResults.size) {
                        val step = stepResults[idx]
                        val vf = VirtualFileManager.getInstance().findFileByUrl("file://${step.filePath}")
                        if (vf != null) {
                            OpenFileDescriptor(project, vf, step.lineNumber - 1, 0).navigate(true)
                        }
                    }
                }
            }
        })

        val missingPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(missingLabel, BorderLayout.NORTH)
            add(JBScrollPane(missingList), BorderLayout.CENTER)
        }

        val resultScroll = JBScrollPane(resultList)

        val dropdownPanel = JPanel(GridLayout(1, 3, 4, 0)).apply {
            add(classDropdown)
            add(screenDropdown)
            add(clearButton)
        }

        val searchPanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(dropdownPanel)
            add(searchField)
        }

        val topResultsPanel = JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(classCountLabel, BorderLayout.SOUTH)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(topResultsPanel, BorderLayout.NORTH)
            add(resultScroll, BorderLayout.CENTER)
        }

        val splitPane = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, missingPanel, bottomPanel).apply {
            resizeWeight = 0.5
            dividerSize = 4
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(statsLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(topPanel, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false).apply {
            // Enable new UI specific features
            isCloseable = false
            isPinnable = true
        }

        // listen for file saves/changes and recompute results once indexing completes
        val connection = project.messageBus.connect()
        connection.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                override fun after(events: MutableList<out com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    // Invalidate cache when files change
                    searchService.invalidateCache()
                    DumbService.getInstance(project).runWhenSmart {
                        refresh() // This will update both UI content and icon
                    }
                }
            }
        )
        
        // Listen for indexing completion to refresh automatically
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : com.intellij.openapi.project.DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    // Refresh when indexing completes to update UI content
                    refresh()
                }
            }
        )
        
        content.setDisposer { connection.disconnect() }

        toolWindow.contentManager.addContent(content)
    }
}