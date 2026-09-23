package com.stepscout.settings

import com.intellij.util.messages.Topic

/** Notified on the project message bus when StepScout settings are applied. */
fun interface StepScoutSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(StepScoutSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
