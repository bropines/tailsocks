package io.github.bropines.tailscaled.admin.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuJsonTest {

    private fun kinds(text: String) = HuJson.tokenize(text).filter { it.type != HuTokenType.SPACE }
        .map { it.type to text.substring(it.start, it.end) }

    @Test
    fun tokensOfHuJsonWithCommentsAndTrailingCommas() {
        val text = """
            // who may talk to whom
            {
              "acls": [ /* the default */
                {"action": "accept", "src": ["*"], "dst": ["*:*"],},
              ],
              "tests": [], "n": -1.5e3, "on": true,
            }
        """.trimIndent()
        val t = kinds(text)
        assertEquals(HuTokenType.COMMENT to "// who may talk to whom", t[0])
        assertTrue(HuTokenType.KEY to "\"acls\"" in t)
        assertTrue(HuTokenType.COMMENT to "/* the default */" in t)
        assertTrue(HuTokenType.STRING to "\"accept\"" in t)
        assertTrue(HuTokenType.KEY to "\"action\"" in t)
        assertTrue(HuTokenType.NUMBER to "-1.5e3" in t)
        assertTrue(HuTokenType.LITERAL to "true" in t)
        assertFalse("nothing in valid HuJSON is an error", t.any { it.first == HuTokenType.ERROR })
        // Every character lands in exactly one token.
        val all = HuJson.tokenize(text)
        assertEquals(text.length, all.sumOf { it.end - it.start })
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertTrue(HuJson.check(text).isEmpty())
    }

    @Test
    fun aKeyIsAStringBeforeAColonEvenPastAComment() {
        val t = kinds("""{"a" /* x */ : "b"}""")
        assertEquals(HuTokenType.KEY to "\"a\"", t[1])
        assertEquals(HuTokenType.STRING to "\"b\"", t[4])
    }

    @Test
    fun unterminatedThingsStopAtTheirLineOrTheEnd() {
        val t = HuJson.tokenize("{\"a\": \"open\n, \"b\": 1}")
        val s = t.first { it.type == HuTokenType.STRING }
        assertEquals("\"open", "{\"a\": \"open\n, \"b\": 1}".substring(s.start, s.end))
        val c = HuJson.tokenize("{ /* never closed").last()
        assertEquals(HuTokenType.COMMENT, c.type)
        assertEquals(17, c.end)
    }

    @Test
    fun checkNamesTheFirstProblemAndItsLine() {
        fun first(text: String) = HuJson.check(text).single()
        first("{\n  \"a\": \"x\n}").let { assertEquals(HuIssueKind.UNTERMINATED_STRING, it.kind); assertEquals(2, it.line) }
        first("{\n  \"a\": 1 /* oops\n}").let { assertEquals(HuIssueKind.UNTERMINATED_COMMENT, it.kind); assertEquals(2, it.line) }
        first("{\n  \"a\": [1, 2\n}").let {
            assertEquals(HuIssueKind.MISMATCHED, it.kind)
            assertEquals(3, it.line)
            assertEquals(2, it.otherLine)
        }
        first("{\n \"a\": {\n").let { assertEquals(HuIssueKind.UNCLOSED, it.kind); assertEquals(2, it.otherLine) }
        first("{\"a\": 1}\n}").let { assertEquals(HuIssueKind.UNEXPECTED, it.kind); assertEquals(2, it.line) }
        first("{\n\"a\" 1}").let { assertEquals(HuIssueKind.MISSING_COLON, it.kind); assertEquals(2, it.line) }
        first("{\"a\": 1\n \"b\": 2}").let { assertEquals(HuIssueKind.MISSING_COMMA, it.kind); assertEquals(2, it.line) }
        first("[1, 2]").let { assertEquals(HuIssueKind.NOT_AN_OBJECT, it.kind) }
        first("  // nothing\n").let { assertEquals(HuIssueKind.EMPTY, it.kind) }
        first("{\"a\": tru}").let { assertEquals(HuIssueKind.UNEXPECTED, it.kind); assertEquals("tru", it.text) }
        first("{\"a\": 01}").let { assertEquals(HuIssueKind.UNEXPECTED, it.kind) }
    }

    @Test
    fun escapesAndLinesInTheTree() {
        val root = HuJson.parse("{\n  \"a\\\"b\": \"x\\u0041\\n\",\n  \"list\": [\n    \"y\",\n  ],\n}")
        assertEquals("xA\n", (root["a\"b"] as HuString).value)
        val list = root["list"] as HuArray
        assertEquals(3, list.line)
        assertEquals(4, list.items.single().line)
        assertEquals(listOf("y"), root["list"].strings())
    }

    @Test
    fun meaningIgnoresCommentsLayoutKeyOrderAndNumberSpelling() {
        val a = "{\n // c\n \"b\": [1, 2,],\n \"a\": {\"x\": 1.50},\n}"
        val b = "{\"a\":{\"x\":1.5},\"b\":[1,2]}"
        assertTrue(HuJson.sameMeaning(a, b))
        assertFalse(HuJson.sameMeaning(a, "{\"a\":{\"x\":1.5},\"b\":[2,1]}"))
        assertFalse("a file that does not parse means nothing", HuJson.sameMeaning(a, "{"))
    }

    @Test
    fun linesMapOffsets() {
        val l = HuJson.Lines("ab\ncd\n")
        assertEquals(3, l.count)
        assertEquals(1, l.lineOf(1))
        assertEquals(2, l.lineOf(3))
        assertEquals(2, l.columnOf(4))
        assertEquals(3, l.startOf(2))
    }
}
