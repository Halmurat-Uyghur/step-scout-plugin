package com.stepscout.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "StepScoutSettings", storages = [Storage("stepscout.xml")])
class StepScoutSettings : PersistentStateComponent<StepScoutSettings.State> {
    data class State(var excludePaths: MutableList<String> = mutableListOf())

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var excludePaths: MutableList<String>
        get() = state.excludePaths
        set(value) { state.excludePaths = value }

    companion object {
        fun getInstance(project: Project): StepScoutSettings = project.service()
    }
}
