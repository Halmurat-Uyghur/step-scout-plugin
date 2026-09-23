package com.stepscout.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepPatternCompilerTest {

    private fun matches(pattern: String, text: String, forceRegex: Boolean = false): Boolean {
        val regex = assertNotNull(StepPatternCompiler.compile(pattern, forceRegex), "pattern should compile: $pattern")
        return regex.matches(text)
    }

    @Test
    fun plainTextMatchesExactly() {
        assertTrue(matches("I log in", "I log in"))
        assertFalse(matches("I log in", "I log in now"))
    }

    @Test
    fun builtInParameterTypes() {
        assertTrue(matches("I have {int} cukes", "I have 42 cukes"))
        assertTrue(matches("I have {int} cukes", "I have -3 cukes"))
        assertFalse(matches("I have {int} cukes", "I have many cukes"))
        assertTrue(matches("it costs {float}", "it costs 3.50"))
        assertTrue(matches("the {word} button", "the submit button"))
        assertFalse(matches("the {word} button", "the big red button"))
        assertTrue(matches("I enter {string}", "I enter \"hello world\""))
        assertTrue(matches("I enter {string}", "I enter 'hello'"))
        assertFalse(matches("I enter {string}", "I enter hello"))
        assertTrue(matches("anything {} goes", "anything at all goes"))
        assertTrue(matches("a {color} ball", "a red ball"))
    }

    @Test
    fun optionalText() {
        assertTrue(matches("I have {int} cucumber(s)", "I have 1 cucumber"))
        assertTrue(matches("I have {int} cucumber(s)", "I have 2 cucumbers"))
    }

    @Test
    fun optionalTextMayContainWhitespace() {
        assertTrue(matches("I am on the( home) page", "I am on the home page"))
        assertTrue(matches("I am on the( home) page", "I am on the page"))
        assertTrue(matches("(the )user logs in", "user logs in"))
        assertTrue(matches("(the )user logs in", "the user logs in"))
    }

    @Test
    fun alternationWithOptionalText() {
        assertTrue(matches("I have {int} cucumber(s) in my belly/stomach", "I have 1 cucumber in my stomach"))
        assertTrue(matches("I have {int} cucumber(s) in my belly/stomach", "I have 2 cucumbers in my belly"))
    }

    @Test
    fun slashDelimitedPatternsAreRegularExpressions() {
        assertTrue(matches("/I have (\\d+) cukes/", "I have 7 cukes"))
        assertFalse(matches("/I have (\\d+) cukes/", "I have x cukes"))
    }

    @Test
    fun alternation() {
        assertTrue(matches("I click/tap the button", "I click the button"))
        assertTrue(matches("I click/tap the button", "I tap the button"))
        assertFalse(matches("I click/tap the button", "I click/tap the button"))
    }

    @Test
    fun escapesAreLiteral() {
        assertTrue(matches("a \\(literal\\) paren", "a (literal) paren"))
        assertTrue(matches("a \\{int\\} brace", "a {int} brace"))
        assertTrue(matches("either\\/or", "either/or"))
    }

    @Test
    fun regexCharactersInExpressionAreLiteral() {
        assertTrue(matches("the price is $5.00?", "the price is $5.00?"))
        assertFalse(matches("the price is 5.00", "the price is 5x00"))
    }

    @Test
    fun anchoredPatternsAreRegularExpressions() {
        assertTrue(matches("^I have (\\d+) cukes$", "I have 12 cukes"))
        assertTrue(matches("^I am on the (.*) page", "I am on the home page"))
        assertFalse(matches("^I have (\\d+) cukes$", "I have x cukes"))
    }

    @Test
    fun legacyAnnotationsAreAlwaysRegex() {
        assertTrue(matches("I have (\\d+) cukes", "I have 5 cukes", forceRegex = true))
    }

    @Test
    fun invalidRegexReturnsNull() {
        assertNull(StepPatternCompiler.compile("^unclosed (group$"))
    }

    @Test
    fun matchingIsCaseSensitiveLikeCucumber() {
        assertFalse(matches("I log in", "i log in"))
    }
}
