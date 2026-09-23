package com.stepscout.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker

@Service(Service.Level.PROJECT)
@State(name = "StepScoutSettings", storages = [Storage("stepscout.xml")])
class StepScoutSettings : PersistentStateComponent<StepScoutSettings.State> {
    data class State(var excludePaths: MutableList<String> = mutableListOf())

    private var state = State()

    /** Incremented whenever the exclusions change, so cached scan results can be invalidated. */
    val modificationTracker = SimpleModificationTracker()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        modificationTracker.incModificationCount()
    }

    var excludePaths: MutableList<String>
        get() = state.excludePaths
        set(value) {
            state.excludePaths = value
            modificationTracker.incModificationCount()
        }

    /** Returns true if [path] contains any of the configured exclusion fragments. */
    fun isExcluded(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        return state.excludePaths.any { exclusion ->
            val normalized = exclusion.trim().replace('\\', '/')
            normalized.isNotEmpty() && normalizedPath.contains(normalized)
        }
    }

    companion object {
        fun getInstance(project: Project): StepScoutSettings = project.service()
    }
}
