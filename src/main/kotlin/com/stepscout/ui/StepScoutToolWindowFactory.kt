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
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.vfs.LocalFileSystem
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
        val statsLabel = JLabel("Scenarios: –  |  Steps: –  |  Features: –")
        val classCountLabel = JLabel("Steps: –")
        val classDropdown = JComboBox<String>().apply {
            toolTipText = "Filter by step class"
        }
        val screenDropdown = JComboBox<String>().apply {
            toolTipText = "Filter by screen"
        }
        val clearButton = JButton(AllIcons.Actions.Rollback).apply {
            toolTipText = "Reset all filters"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = java.awt.Insets(0, 0, 0, 0)
            preferredSize = java.awt.Dimension(24, 24)
        }
        var fullClassCounts: Map<String, Int> = emptyMap()
        var classCounts: Map<String, Int> = emptyMap()
        var displayToFull: Map<String, Set<String>> = emptyMap()
        var classKeys: List<String> = emptyList()
        var selectedClasses: Set<String>? = null
        var selectedDisplay: String? = null
        var screenCounts: Map<String, Int> = emptyMap()
        var screenKeys: List<String> = emptyList()
        var selectedScreen: String? = null
        var refreshGen = 0L
        var updateGen = 0L
        var onRefreshComplete: () -> Unit = {}
        var updatingModels = false

        val missingListModel = DefaultListModel<String>()
        val missingList = JBList(missingListModel)
        var missingSteps: List<MissingStep> = emptyList()

        // refresh logic must be defined before the button references it
        fun refresh() {
            val myGen = ++refreshGen
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
                        if (refreshGen != myGen) return@invokeLater

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

                    // Snapshot filter state before replacing models, then suppress
                    // action listeners during model updates to prevent spurious
                    // updateResults() calls and filter state loss.
                    val prevDisplay = selectedDisplay
                    val prevScreen = selectedScreen
                    updatingModels = true
                    try {
                        val model = DefaultComboBoxModel<String>()
                        model.addElement("All Classes")
                        classKeys.forEach { name ->
                            model.addElement("$name (${classCounts[name]})")
                        }
                        classDropdown.model = model
                        if (prevDisplay != null && prevDisplay in classKeys) {
                            classDropdown.selectedIndex = classKeys.indexOf(prevDisplay) + 1
                            selectedClasses = displayToFull[prevDisplay]
                        } else {
                            selectedClasses = null
                            selectedDisplay = null
                            classDropdown.selectedIndex = 0
                        }

                        screenCounts = screenData
                        screenKeys = screenCounts.keys.sorted()

                        val screenModel = DefaultComboBoxModel<String>()
                        screenModel.addElement("All Screens")
                        screenKeys.forEach { name ->
                            screenModel.addElement("$name (${screenCounts[name]})")
                        }
                        screenDropdown.model = screenModel
                        if (prevScreen != null && prevScreen in screenKeys) {
                            screenDropdown.selectedIndex = screenKeys.indexOf(prevScreen) + 1
                        } else {
                            selectedScreen = null
                            screenDropdown.selectedIndex = 0
                        }
                    } finally {
                        updatingModels = false
                    }
                    
                    missingSteps = steps
                    missingListModel.clear()
                    steps.forEach {
                        val fileName = java.nio.file.Paths.get(it.filePath).fileName.toString()
                        missingListModel.addElement("${it.text} - $fileName:${it.lineNumber}")
                    }
                    missingLabel.text = "Missing ${steps.size} Steps"
                    statsLabel.text = "Scenarios: $scenarioCountResult  |  Steps: $stepCountResult  |  Features: $featureCountResult"
                    
                    // Update tool window icon based on missing steps
                    // Note: Icon is also managed by StepScoutIconService for automatic updates
                    if (steps.isNotEmpty()) {
                        toolWindow.setIcon(AllIcons.General.Error)
                    } else {
                        toolWindow.setIcon(defaultIcon)
                    }
                    
                    classCountLabel.text = "Steps: $stepCountResult"

                    // Refresh the step results list to reflect new data
                    onRefreshComplete()
                        } catch (e: Exception) {
                            // Handle UI update errors gracefully - log but don't crash
                            // Could add logging here if needed
                        }
                    }
                }
            }
        }

        val refreshAction = object : AnAction("Refresh", "Refresh step definitions and missing steps", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { refresh() }
        }

        val settingsAction = object : AnAction("Settings", "StepScout Settings (exclude paths)", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project, com.stepscout.settings.StepScoutConfigurable::class.java
                )
            }
        }

        val actionGroup = DefaultActionGroup().apply {
            add(refreshAction)
            add(settingsAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true).apply {
            targetComponent = null
        }

        missingList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = missingList.locationToIndex(e.point)
                    if (idx >= 0 && idx < missingSteps.size) {
                        val step = missingSteps[idx]
                        val vf = LocalFileSystem.getInstance().findFileByPath(step.filePath)
                        if (vf != null) {
                            OpenFileDescriptor(project, vf, step.lineNumber - 1, 0).navigate(true)
                        }
                    }
                }
            }
        })

        // Initial refresh — runWhenSmart ensures refresh runs after indexing completes
        DumbService.getInstance(project).runWhenSmart {
            refresh()
        }

        val listModel = DefaultListModel<String>()
        val resultList = JBList(listModel)
        var stepResults: List<StepResult> = emptyList()

        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Search step name"
        }

        fun updateResults() {
            val myGen = ++updateGen
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
                                results.size
                            } else {
                                searchService.countFilteredSteps(classFilter, screenFilter)
                            }
                            Pair(results, total)
                        } catch (e: Exception) {
                            Pair(emptyList(), 0)
                        }
                    }
                    
                    ApplicationManager.getApplication().invokeLater {
                        // Check if project is disposed before updating UI
                        if (project.isDisposed) return@invokeLater
                        if (updateGen != myGen) return@invokeLater

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

        onRefreshComplete = { updateResults() }

        val debounceTimer = javax.swing.Timer(200) { updateResults() }.apply {
            isRepeats = false
        }

        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { debounceTimer.restart() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { debounceTimer.restart() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { debounceTimer.restart() }
        })

        classDropdown.addActionListener {
            if (updatingModels) return@addActionListener
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
            if (updatingModels) return@addActionListener
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
                        val vf = LocalFileSystem.getInstance().findFileByPath(step.filePath)
                        if (vf != null) {
                            OpenFileDescriptor(project, vf, step.lineNumber - 1, 0).navigate(true)
                        }
                    }
                }
            }
        })

        // --- Missing Steps section ---
        val missingScroll = JBScrollPane(missingList).apply {
            border = JBUI.Borders.emptyLeft(13)
        }
        val missingPanel = JPanel(BorderLayout()).apply {
            add(missingLabel.apply { border = JBUI.Borders.empty(0, 13, 4, 0) }, BorderLayout.NORTH)
            add(missingScroll, BorderLayout.CENTER)
        }

        // --- Search & Results section ---
        val resultScroll = JBScrollPane(resultList).apply {
            border = JBUI.Borders.emptyLeft(13)
        }
        val dropdownRow = JPanel(GridLayout(1, 2, 4, 0)).apply {
            add(classDropdown)
            add(screenDropdown)
        }
        val filterBar = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4, 4, 4, 8)
            add(dropdownRow, BorderLayout.CENTER)
            add(clearButton, BorderLayout.EAST)
        }
        val searchBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4, 4, 4)
            add(searchField, BorderLayout.CENTER)
        }
        val bottomHeader = JPanel(BorderLayout()).apply {
            add(filterBar, BorderLayout.NORTH)
            add(searchBar, BorderLayout.CENTER)
            add(classCountLabel.apply { border = JBUI.Borders.empty(0, 4, 2, 0) }, BorderLayout.SOUTH)
        }
        val bottomTop = JPanel(BorderLayout()).apply {
            add(javax.swing.JSeparator(), BorderLayout.NORTH)
            add(bottomHeader, BorderLayout.CENTER)
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(bottomTop, BorderLayout.NORTH)
            add(resultScroll, BorderLayout.CENTER)
        }

        // --- Layout ---
        val splitPane = com.intellij.ui.OnePixelSplitter(true, 0.4f).apply {
            firstComponent = missingPanel
            secondComponent = bottomPanel
        }
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 13, 4, 0)
            add(statsLabel, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout()).apply {
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
                    val relevant = events.any { event ->
                        val path = event.path
                        path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".feature")
                    }
                    if (!relevant) return
                    searchService.invalidateCache()
                    DumbService.getInstance(project).runWhenSmart {
                        refresh()
                    }
                }
            }
        )
        connection.subscribe(
            com.stepscout.settings.StepScoutSettingsListener.TOPIC,
            object : com.stepscout.settings.StepScoutSettingsListener {
                override fun settingsChanged() {
                    searchService.invalidateCache()
                    DumbService.getInstance(project).runWhenSmart {
                        refresh()
                    }
                }
            }
        )

        content.setDisposer { connection.disconnect() }

        toolWindow.contentManager.addContent(content)
    }
}