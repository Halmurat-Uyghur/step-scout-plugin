package com.stepscout.services

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.stepscout.settings.StepScoutSettings

/**
 * Exercises step discovery and missing-step detection against real PSI.
 */
class StepScoutPlatformTest : LightJavaCodeInsightFixtureTestCase() {

    // A real JDK is needed so that String constants (e.g. @Given(PREFIX + "...")) can be evaluated.
    override fun getProjectDescriptor(): LightProjectDescriptor = JDK_DESCRIPTOR

    override fun setUp() {
        super.setUp()
        for (keyword in listOf("Given", "When", "Then")) {
            myFixture.addClass(
                """
                package io.cucumber.java.en;
                @io.cucumber.java.StepDefinitionAnnotation
                public @interface $keyword { String value(); }
                """.trimIndent()
            )
        }
        myFixture.addClass(
            """
            package io.cucumber.java;
            public @interface StepDefinitionAnnotation {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package io.cucumber.java.fr;
            @io.cucumber.java.StepDefinitionAnnotation
            public @interface Soit { String value(); }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package steps;
            import io.cucumber.java.en.*;
            public class LoginSteps {
                static final String PREFIX = "Login: ";
                @Given("I have {int} cucumber(s)") public void cukes(int n) {}
                @When("I click/tap the {string} button") public void click(String b) {}
                @Then("^the total is (\\d+)$") public void total(int t) {}
                @Given(PREFIX + "I open the app") public void open() {}
                @io.cucumber.java.fr.Soit("un utilisateur") public void utilisateur() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "steps/KotlinSteps.kt",
            """
            package steps
            import io.cucumber.java8.En
            class KotlinSteps : En {
                init {
                    Given("^I wait (\\d+) seconds$") { s: Int -> }
                    When("I say {string}") { s: String -> }
                }
            }
            """.trimIndent()
        )
    }

    private fun definitions() = runReadAction { StepSearchService.getInstance(project).getStepDefinitions() }

    fun testDiscoversJavaAndKotlinDefinitions() {
        val expressions = definitions().map { it.expression }.toSet()
        assertTrue(expressions.contains("I have {int} cucumber(s)"))
        assertTrue(expressions.contains("I click/tap the {string} button"))
        assertTrue(expressions.contains("^the total is (\\d+)$"))
        assertTrue("constant expressions are evaluated", expressions.contains("Login: I open the app"))
        assertTrue("localized annotations are found", expressions.contains("un utilisateur"))
        assertTrue("Kotlin escapes are unescaped", expressions.contains("^I wait (\\d+) seconds$"))
        assertTrue(expressions.contains("I say {string}"))
    }

    fun testScreenNamesAndClasses() {
        val service = StepSearchService.getInstance(project)
        assertEquals(1, runReadAction { service.getScreenNames() }["Login"])
        assertEquals(5, runReadAction { service.getStepClasses() }["steps.LoginSteps"])
    }

    fun testFindsMissingStepsAndCountsScenarios() {
        myFixture.addFileToProject(
            "features/cukes.feature",
            """
            Feature: Cukes
              Background:
                Given I have 1 cucumber

              Scenario: Defined steps
                Given I have 5 cucumbers
                When I tap the "OK" button
                Then the total is 10
                And I wait 3 seconds
                And I say "hi"

              Scenario: Undefined step
                Given I do something undefined

              Scenario Outline: Outline
                Given I have <n> cucumbers
                Then the total is <total>

                Examples:
                  | n | total |
                  | 1 | 2     |
                  | 3 | many  |
            """.trimIndent()
        )

        val scan = runReadAction { MissingStepService.getInstance(project).scanFeatures() }

        assertEquals(1, scan.featureCount)
        // Two plain scenarios + two example rows; the Background is not a scenario.
        assertEquals(4, scan.scenarioCount)
        assertEquals(
            listOf("I do something undefined", "the total is <total>"),
            scan.missingSteps.map { it.text }
        )
        assertEquals(13, scan.missingSteps.first().lineNumber)
    }

    fun testExcludedPathsAreSkipped() {
        myFixture.addFileToProject("excluded/skip.feature", "Feature: Skip\n  Scenario: S\n    Given nothing matches\n")
        StepScoutSettings.getInstance(project).excludePaths = mutableListOf("excluded/")
        try {
            val scan = runReadAction { MissingStepService.getInstance(project).scanFeatures() }
            assertEquals(0, scan.featureCount)
            assertTrue(scan.missingSteps.isEmpty())
        } finally {
            StepScoutSettings.getInstance(project).excludePaths = mutableListOf()
        }
    }

    private companion object {
        val JDK_DESCRIPTOR = DefaultLightProjectDescriptor {
            JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
        }
    }
}
