package io.github.bropines.tailscaled.admin.policy.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `when`: names, `!`, `&&` before `||`, parentheses; and what will not read. */
class TemplateConditionsTest {

    private fun eval(c: String, vararg on: String) = Conditions.parse(c).eval { it in on }

    @Test
    fun namesAndOperators() {
        assertTrue(eval("headscale", "headscale"))
        assertFalse(eval("!headscale", "headscale"))
        assertTrue(eval("check:a && param:b", "check:a", "param:b"))
        assertFalse(eval("check:a && param:b", "check:a"))
        assertTrue(eval("check:a || param:b", "param:b"))
        assertTrue(eval("!!check:a", "check:a"))
        assertTrue(eval("param:mode=check", "param:mode=check"))
    }

    @Test
    fun andBindsBeforeOr() {
        // a || (b && c)
        assertTrue(eval("a || b && c", "a"))
        assertFalse(eval("a || b && c", "b"))
        // (a || b) && c
        assertFalse(eval("(a || b) && c", "a"))
        assertTrue(eval("(a || b) && c", "a", "c"))
    }

    @Test
    fun theTaildriveSuggestion() {
        val c = "!(check:attrs && check:grant)"
        assertTrue(eval(c))
        assertTrue(eval(c, "check:attrs"))
        assertFalse(eval(c, "check:attrs", "check:grant"))
        assertEquals(listOf("check:attrs", "check:grant"), Conditions.parse(c).names)
    }

    @Test
    fun whatDoesNotRead() {
        for (bad in listOf("", "&&", "a &&", "(a", "a)", "a b", "A", "a & b", "a | b", "x".repeat(301), "param:x y")) {
            assertThrows(bad, ConditionException::class.java) { Conditions.parse(bad) }
        }
    }
}
