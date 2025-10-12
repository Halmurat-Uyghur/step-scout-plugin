package com.stepscout.services

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class StepSearchServiceTest {
    @Test
    fun getStepDefinitionsReturnsOverride() {
        val defs = listOf(
            StepDefinition(Regex("^login$"), "/tmp/Login.kt", 1, "LoginSteps", "login")
        )
        val service = StepSearchService(mockk(), defs)
        assertEquals(defs, service.getStepDefinitions())
    }

    @Test
    fun findStepsFiltersAndScores() {
        val defs = listOf(
            StepDefinition(Regex("^I login$"), "/tmp/Login.kt", 1, "LoginSteps", "login"),
            StepDefinition(Regex("^I logout$"), "/tmp/Auth.kt", 2, "AuthSteps", "auth")
        )
        val service = StepSearchService(mockk(), defs)
        val results = service.findSteps("login", classFilter = setOf("LoginSteps"))
        assertEquals(1, results.size)
        assertEquals("I login", results[0].text)
    }
}
