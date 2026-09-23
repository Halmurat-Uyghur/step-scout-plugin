package com.stepscout.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepMatcherTest {

    private fun def(expression: String, className: String = "Steps", screen: String = "") = StepDefinition(
        expression = expression,
        regex = StepPatternCompiler.compile(expression)!!,
        fileUrl = "file:///tmp/$className.java",
        filePath = "/tmp/$className.java",
        lineNumber = 1,
        className = className,
        screenName = screen
    )

    @Test
    fun blankQueryReturnsAllSorted() {
        val results = StepMatcher.findSteps(listOf(def("b step"), def("a step")), "")
        assertEquals(listOf("a step", "b step"), results.map { it.text })
    }

    @Test
    fun filtersByClassAndScores() {
        val defs = listOf(def("I login", "LoginSteps"), def("I logout", "AuthSteps"))
        val results = StepMatcher.findSteps(defs, "login", classFilter = setOf("LoginSteps"))
        assertEquals(listOf("I login"), results.map { it.text })
    }

    @Test
    fun filtersByScreen() {
        val defs = listOf(def("Login: I tap submit", screen = "Login"), def("Home: I tap menu", screen = "Home"))
        val results = StepMatcher.findSteps(defs, "", screenFilter = "Home")
        assertEquals(listOf("Home: I tap menu"), results.map { it.text })
    }

    @Test
    fun displaysOriginalExpressionWithParameterTypes() {
        val results = StepMatcher.findSteps(listOf(def("I have {int} cukes")), "cukes")
        assertEquals("I have {int} cukes", results.single().text)
    }

    @Test
    fun directMatchesRankAboveFuzzyMatches() {
        val defs = listOf(def("open the settings page"), def("settings are open"))
        val results = StepMatcher.findSteps(defs, "settings are")
        assertEquals("settings are open", results.first().text)
    }

    @Test
    fun camelCaseQueryIsSplitIntoTokens() {
        assertEquals(listOf("user", "profile"), StepMatcher.tokenize("userProfile"))
        val results = StepMatcher.findSteps(listOf(def("the user opens the profile"), def("the admin logs in")), "userProfile")
        assertEquals(listOf("the user opens the profile"), results.map { it.text })
    }

    @Test
    fun queriesWithPunctuationMatch() {
        val defs = listOf(def("I log-in as admin"), def("I have {int} cukes"), def("Login: I tap submit"))
        // "log-in" also fuzzily matches "Login:", but the exact match ranks first.
        assertEquals("I log-in as admin", StepMatcher.findSteps(defs, "log-in").first().text)
        assertEquals(listOf("I have {int} cukes"), StepMatcher.findSteps(defs, "{int}").map { it.text })
        assertEquals(listOf("Login: I tap submit"), StepMatcher.findSteps(defs, "Login:").map { it.text })
    }

    @Test
    fun nonMatchingQueryReturnsNothing() {
        assertTrue(StepMatcher.findSteps(listOf(def("I login")), "checkout").isEmpty())
    }

    @Test
    fun extractsScreenNameOnlyFromFirstWord() {
        assertEquals("Login", StepMatcher.extractScreenName("Login: I tap submit"))
        assertEquals("", StepMatcher.extractScreenName("the time is 12:00"))
        assertEquals("", StepMatcher.extractScreenName(":leading colon"))
        assertEquals("Login", StepMatcher.extractScreenName("^Login: I open the app$"))
    }
}
