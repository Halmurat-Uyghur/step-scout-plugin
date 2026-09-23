package com.stepscout.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.stepscout.services.MissingStep
import com.stepscout.services.MissingStepService
import com.stepscout.services.StepResult
import com.stepscout.services.StepSearchService
import com.stepscout.settings.StepScoutSettingsListener
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.event.DocumentEvent

private data class Stats(
    val missing: List<MissingStep>,
    val stepCount: Int,
    val featureCount: Int,
    val scenarioCount: Int,
    val classCounts: Map<String, Int>,
    val screenCounts: Map<String, Int>
)

private data class SearchData(val results: List<StepResult>, val total: Int)

/**
 * The StepScout tool window UI. All PSI work runs in cancellable, non-blocking read actions
 * off the EDT; results are applied on the EDT.
 */
internal class StepScoutPanel(private val project: Project, private val toolWindow: ToolWindow) {

    private val disposable = toolWindow.disposable
    private val defaultIcon = IconLoader.getIcon("/icons/pluginIconSmall.svg", StepScoutPanel::class.java)
    private val refreshAlarm = Alarm(disposable)

    // Coalescing keys: a newer request of the same kind cancels the one in flight.
    private val refreshKey = Any()
    private val searchKey = Any()

    private val missingLabel = JLabel("Missing Steps")
    private val statsLabel = JLabel("")
    private val countLabel = JLabel("")
    private val classDropdown = JComboBox<String>().apply { prototypeDisplayValue = "All Classes (999)" }
    private val screenDropdown = JComboBox<String>().apply { prototypeDisplayValue = "All Screens (999)" }
    private val searchField = SearchTextField().apply { textEditor.emptyText.text = "Search step name" }

    private val missingListModel = DefaultListModel<String>()
    private val missingList = JBList(missingListModel)
    private val resultListModel = DefaultListModel<String>()
    private val resultList = JBList(resultListModel)

    private var missingSteps: List<MissingStep> = emptyList()
    private var stepResults: List<StepResult> = emptyList()

    /** Simple class name shown in the dropdown -> fully-qualified class names it stands for. */
    private var classKeys: List<String> = emptyList()
    private var displayToFull: Map<String, Set<String>> = emptyMap()
    private var screenKeys: List<String> = emptyList()
    private var selectedClass: String? = null
    private var selectedScreen: String? = null

    /** Suppresses dropdown listeners while models are rebuilt programmatically. */
    private var updatingFilters = false

    val component: JComponent = buildLayout()

    init {
        installListeners()
    }

    fun start() {
        val connection = project.messageBus.connect(disposable)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevant(it.path) }) scheduleRefresh()
            }
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = scheduleRefresh()
        })
        connection.subscribe(StepScoutSettingsListener.TOPIC, object : StepScoutSettingsListener {
            override fun settingsChanged() = scheduleRefresh()
        })
        refresh()
    }

    private fun isRelevant(path: String): Boolean =
        RELEVANT_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }

    /** Debounces bursts of file events (e.g. VCS updates) into a single refresh. */
    private fun scheduleRefresh() {
        if (refreshAlarm.isDisposed) return
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(::refresh, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        ReadAction.nonBlocking<Stats> {
            val searchService = StepSearchService.getInstance(project)
            val scan = MissingStepService.getInstance(project).scanFeatures()
            Stats(
                missing = scan.missingSteps,
                stepCount = searchService.countStepDefinitions(),
                featureCount = scan.featureCount,
                scenarioCount = scan.scenarioCount,
                classCounts = searchService.getStepClasses(),
                screenCounts = searchService.getScreenNames()
            )
        }
            .inSmartMode(project)
            .expireWith(disposable)
            .coalesceBy(refreshKey)
            .finishOnUiThread(ModalityState.any(), ::applyStats)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyStats(stats: Stats) {
        updateFilterModels(stats.classCounts, stats.screenCounts)

        missingSteps = stats.missing
        missingListModel.clear()
        missingListModel.addAll(stats.missing.map { "${it.text} - ${fileName(it.filePath)}:${it.lineNumber}" })
        missingLabel.text = "Missing ${stats.missing.size} Steps"
        statsLabel.text = "<html>Total Scenarios: ${stats.scenarioCount}<br>Steps: ${stats.stepCount}" +
            "<br>Features: ${stats.featureCount}</html>"
        toolWindow.setIcon(if (stats.missing.isNotEmpty()) AllIcons.General.Error else defaultIcon)

        updateResults()
    }

    /** Rebuilds the dropdowns, keeping the user's current selection when it still exists. */
    private fun updateFilterModels(classData: Map<String, Int>, screenData: Map<String, Int>) {
        val counts = sortedMapOf<String, Int>()
        val mapping = mutableMapOf<String, MutableSet<String>>()
        classData.forEach { (full, count) ->
            val simple = full.substringAfterLast('.')
            counts.merge(simple, count, Int::plus)
            mapping.getOrPut(simple) { mutableSetOf() }.add(full)
        }
        classKeys = counts.keys.toList()
        displayToFull = mapping
        screenKeys = screenData.keys.sorted()

        if (selectedClass !in counts) selectedClass = null
        if (selectedScreen !in screenData) selectedScreen = null

        updatingFilters = true
        try {
            classDropdown.model = DefaultComboBoxModel(
                (listOf("All Classes") + classKeys.map { "$it (${counts[it]})" }).toTypedArray()
            )
            classDropdown.selectedIndex = selectedClass?.let { classKeys.indexOf(it) + 1 } ?: 0
            screenDropdown.model = DefaultComboBoxModel(
                (listOf("All Screens") + screenKeys.map { "$it (${screenData[it]})" }).toTypedArray()
            )
            screenDropdown.selectedIndex = selectedScreen?.let { screenKeys.indexOf(it) + 1 } ?: 0
        } finally {
            updatingFilters = false
        }
    }

    private fun updateResults() {
        val query = searchField.text
        val classFilter = selectedClass?.let { displayToFull[it] }
        val screenFilter = selectedScreen
        val filterLabel = listOfNotNull(selectedClass, selectedScreen).joinToString(" / ").ifEmpty { "Steps" }

        ReadAction.nonBlocking<SearchData> {
            val service = StepSearchService.getInstance(project)
            val all = service.findSteps("", classFilter, screenFilter)
            val results = if (query.isBlank()) all else service.findSteps(query, classFilter, screenFilter)
            SearchData(results, all.size)
        }
            .inSmartMode(project)
            .expireWith(disposable)
            .coalesceBy(searchKey)
            .finishOnUiThread(ModalityState.any()) { data ->
                stepResults = data.results
                resultListModel.clear()
                resultListModel.addAll(data.results.map { it.text })
                countLabel.text = if (query.isBlank()) {
                    "$filterLabel: ${data.total}"
                } else {
                    "Results: ${data.results.size} (of ${data.total})"
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun installListeners() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateResults()
        })

        classDropdown.addActionListener {
            if (updatingFilters) return@addActionListener
            val idx = classDropdown.selectedIndex
            selectedClass = if (idx <= 0) null else classKeys.getOrNull(idx - 1)
            updateResults()
        }

        screenDropdown.addActionListener {
            if (updatingFilters) return@addActionListener
            val idx = screenDropdown.selectedIndex
            selectedScreen = if (idx <= 0) null else screenKeys.getOrNull(idx - 1)
            updateResults()
        }

        missingList.addMouseListener(doubleClickListener { idx ->
            missingSteps.getOrNull(idx)?.let { navigate(it.fileUrl, it.lineNumber) }
        })
        resultList.addMouseListener(doubleClickListener { idx ->
            stepResults.getOrNull(idx)?.let { navigate(it.fileUrl, it.lineNumber) }
        })
    }

    private fun clearFilters() {
        updatingFilters = true
        try {
            selectedClass = null
            selectedScreen = null
            classDropdown.selectedIndex = 0
            screenDropdown.selectedIndex = 0
        } finally {
            updatingFilters = false
        }
        // Setting the text triggers updateResults() only if it changes, so update explicitly.
        searchField.text = ""
        updateResults()
    }

    private fun doubleClickListener(onDoubleClick: (Int) -> Unit) = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount != 2) return
            val list = e.source as? JBList<*> ?: return
            val idx = list.locationToIndex(e.point)
            if (idx >= 0 && list.getCellBounds(idx, idx)?.contains(e.point) == true) onDoubleClick(idx)
        }
    }

    private fun navigate(fileUrl: String, lineNumber: Int) {
        val vf = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return
        OpenFileDescriptor(project, vf, (lineNumber - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun buildLayout(): JComponent {
        val refreshButton = JButton("Refresh").apply { addActionListener { refresh() } }
        val clearButton = JButton("Clear All").apply { addActionListener { clearFilters() } }

        val missingPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(missingLabel, BorderLayout.NORTH)
            add(JBScrollPane(missingList), BorderLayout.CENTER)
        }

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
            add(countLabel, BorderLayout.SOUTH)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(topResultsPanel, BorderLayout.NORTH)
            add(JBScrollPane(resultList), BorderLayout.CENTER)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, missingPanel, bottomPanel).apply {
            resizeWeight = 0.5
            dividerSize = 4
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(statsLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(topPanel, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
        }
    }

    private companion object {
        const val REFRESH_DELAY_MS = 500L
        val RELEVANT_EXTENSIONS = listOf(".feature", ".java", ".kt")
    }
}
