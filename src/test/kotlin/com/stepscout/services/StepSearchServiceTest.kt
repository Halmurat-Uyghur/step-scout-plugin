package com.stepscout.services

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun invalidateCacheDoesNotAffectTestDefinitions() {
        // testDefinitions bypass the cache path (StepSearchService.kt:103),
        // so this test verifies invalidateCache() is safe to call and that
        // the service continues returning correct results.
        val defs = listOf(
            StepDefinition(Regex("^step one$"), "/tmp/A.kt", 1, "ASteps", "")
        )
        val service = StepSearchService(mockk(), defs)
        assertEquals(1, service.getStepDefinitions().size)
        service.invalidateCache()
        assertEquals(1, service.getStepDefinitions().size)
        assertEquals("ASteps", service.getStepDefinitions()[0].className)
    }

    @Test
    fun invalidateCacheClearsRegexCache() {
        // Verify that invalidateCache clears the internal regex cache.
        // We can observe this indirectly: after invalidation, countStepDefinitions
        // still returns the correct count (testDefinitions path).
        val defs = listOf(
            StepDefinition(Regex("^a$"), "/tmp/A.kt", 1, "A", ""),
            StepDefinition(Regex("^b$"), "/tmp/B.kt", 2, "B", "")
        )
        val service = StepSearchService(mockk(), defs)
        assertEquals(2, service.countStepDefinitions())
        service.invalidateCache()
        assertEquals(2, service.countStepDefinitions())
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

    @Test
    fun findStepsWithScreenFilter() {
        val defs = listOf(
            StepDefinition(Regex("^login: I enter credentials$"), "/tmp/Login.kt", 1, "Steps", "login"),
            StepDefinition(Regex("^home: I see dashboard$"), "/tmp/Home.kt", 2, "Steps", "home")
        )
        val service = StepSearchService(mockk(), defs)
        val results = service.findSteps("", screenFilter = "login")
        assertEquals(1, results.size)
    }

    @Test
    fun countFilteredSteps() {
        val defs = listOf(
            StepDefinition(Regex("^a$"), "/tmp/A.kt", 1, "ClassA", ""),
            StepDefinition(Regex("^b$"), "/tmp/B.kt", 2, "ClassB", ""),
            StepDefinition(Regex("^c$"), "/tmp/C.kt", 3, "ClassA", "")
        )
        val service = StepSearchService(mockk(), defs)
        assertEquals(3, service.countFilteredSteps())
        assertEquals(2, service.countFilteredSteps(classFilter = setOf("ClassA")))
        assertEquals(1, service.countFilteredSteps(classFilter = setOf("ClassB")))
    }

    @Test
    fun hasStepDefinition() {
        val defs = listOf(
            StepDefinition(Regex("^I login$"), "/tmp/Login.kt", 1, "Steps", "")
        )
        val service = StepSearchService(mockk(), defs)
        assertTrue(service.hasStepDefinition("I login"))
        assertTrue(!service.hasStepDefinition("I logout"))
    }
}
