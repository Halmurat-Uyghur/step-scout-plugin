package com.stepscout.ui

import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Drives the tool window panel end to end: the first refresh must populate the stats
 * rather than fail while rebuilding the (initially unselected) filter dropdowns.
 */
class StepScoutPanelTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package io.cucumber.java;
            public @interface StepDefinitionAnnotation {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package io.cucumber.java.en;
            @io.cucumber.java.StepDefinitionAnnotation
            public @interface Given { String value(); }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package steps;
            import io.cucumber.java.en.Given;
            public class LoginSteps {
                @Given("I open the app") public void open() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "features/login.feature",
            """
            Feature: Login
              Scenario: Open
                Given I open the app
                Given I am missing
            """.trimIndent()
        )
    }

    fun testFirstRefreshPopulatesStats() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        try {
            val panel = StepScoutPanel(project, toolWindow)
            panel.start()

            val stats = waitForStats(panel.component)
            assertEquals("Scenarios: 1  |  Steps: 1  |  Features: 1", stats.text)
            assertTrue(labels(panel.component).any { it.text == "Missing 1 Steps" })
        } finally {
            Disposer.dispose(toolWindow.disposable)
        }
    }

    private fun waitForStats(root: JComponent): JLabel {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            labels(root).firstOrNull { it.text.startsWith("Scenarios: ") && !it.text.contains('–') }
                ?.let { return it }
        }
        fail("Stats were never populated: ${labels(root).map { it.text }}")
        error("unreachable")
    }

    private fun labels(root: JComponent): List<JLabel> =
        UIUtil.findComponentsOfType(root, JLabel::class.java)
}
