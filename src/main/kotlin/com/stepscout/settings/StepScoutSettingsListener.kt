package com.stepscout.settings

import com.intellij.util.messages.Topic

interface StepScoutSettingsListener {
    companion object {
        val TOPIC = Topic.create("StepScout settings changed", StepScoutSettingsListener::class.java)
    }

    fun settingsChanged()
}
