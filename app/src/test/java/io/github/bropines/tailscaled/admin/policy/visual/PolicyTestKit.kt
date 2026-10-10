package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuArray
import io.github.bropines.tailscaled.admin.policy.HuField
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuNode
import io.github.bropines.tailscaled.admin.policy.HuObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Fixtures and checks shared by the visual editor's tests. */
object PolicyTestKit {

    /** The author's real policy, names anonymised: tabs, aligned values, trailing commas, Russian comments. */
    val sample: String by lazy {
        requireNotNull(PolicyTestKit::class.java.getResource("/admin/policy/visual/sample.hujson")) { "no sample policy" }.readText()
    }

    // ---- meaning: the expected tree, built on HuJson's own nodes ----

    fun json(value: String): HuNode = HuJson.parse("{\"v\": $value}")["v"]!!

    private fun step(node: HuNode, s: Any): HuNode = when (s) {
        is String -> (node as HuObject)[s] ?: error("no $s")
        is Int -> (node as HuArray).items[s]
        else -> error("bad step $s")
    }

    /** [root] with the container at [path] replaced by [change] of it. */
    private fun rebuild(root: HuNode, path: List<Any>, change: (HuNode) -> HuNode): HuNode {
        if (path.isEmpty()) return change(root)
        val head = path.first()
        val child = step(root, head)
        val updated = rebuild(child, path.drop(1), change)
        return when (head) {
            is String -> {
                val o = root as HuObject
                val last = o.fields.indexOfLast { it.name == head }
                HuObject(o.fields.mapIndexed { i, f -> if (i == last) HuField(f.name, updated, f.line) else f }, o.line)
            }
            else -> {
                val a = root as HuArray
                HuArray(a.items.mapIndexed { i, n -> if (i == head) updated else n }, a.line)
            }
        }
    }

    fun setAt(root: HuNode, path: List<Any>, value: HuNode): HuNode = rebuild(root, path.dropLast(1)) { parent ->
        when (val k = path.last()) {
            is String -> {
                val o = parent as HuObject
                if (o[k] != null) HuObject(o.fields.map { if (it.name == k) HuField(k, value, it.line) else it }, o.line)
                else HuObject(o.fields + HuField(k, value, 0), o.line)
            }
            else -> HuArray((parent as HuArray).items.mapIndexed { i, n -> if (i == k) value else n }, parent.line)
        }
    }

    fun insertAt(root: HuNode, arrayPath: List<Any>, index: Int, value: HuNode): HuNode = rebuild(root, arrayPath) { a ->
        HuArray((a as HuArray).items.toMutableList().also { it.add(index, value) }, a.line)
    }

    fun removeAt(root: HuNode, path: List<Any>): HuNode = rebuild(root, path.dropLast(1)) { parent ->
        when (val k = path.last()) {
            is String -> HuObject((parent as HuObject).fields.filter { it.name != k }, parent.line)
            else -> HuArray((parent as HuArray).items.filterIndexed { i, _ -> i != k }, parent.line)
        }
    }

    fun renameAt(root: HuNode, path: List<Any>, newKey: String): HuNode = rebuild(root, path.dropLast(1)) { parent ->
        val o = parent as HuObject
        HuObject(o.fields.map { if (it.name == path.last()) HuField(newKey, it.value, it.line) else it }, o.line)
    }

    fun moveAt(root: HuNode, arrayPath: List<Any>, from: Int, to: Int): HuNode = rebuild(root, arrayPath) { a ->
        val items = (a as HuArray).items.toMutableList()
        val x = items.removeAt(from)
        items.add(to, x)
        HuArray(items, a.line)
    }

    /** [after] says exactly what [expected] says. */
    fun assertMeaning(expected: HuNode, after: String) {
        assertTrue("does not parse:\n$after", HuJson.check(after).isEmpty())
        assertEquals(HuJson.canonical(expected), HuJson.canonical(HuJson.parse(after)))
    }

    fun parsed(text: String): HuNode = HuJson.parse(text)

    /**
     * The one window in which [before] and [after] differ, as (removed, inserted) text with the
     * offset where it starts: everything outside it is byte-identical by construction.
     */
    data class Window(val at: Int, val removed: String, val inserted: String)

    fun window(before: String, after: String): Window {
        var p = 0
        while (p < before.length && p < after.length && before[p] == after[p]) p++
        var s = 0
        while (s < before.length - p && s < after.length - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        return Window(p, before.substring(p, before.length - s), after.substring(p, after.length - s))
    }

    /** Every line of [before] that is not inside [lines] (1-based, inclusive) is in [after], in order and unchanged. */
    fun assertOtherLinesKept(before: String, after: String, lines: IntRange) {
        val b = before.split('\n')
        val keptBefore = b.filterIndexed { i, _ -> (i + 1) !in lines }
        val a = after.split('\n')
        // keptBefore must be a subsequence of a.
        var j = 0
        for (line in a) if (j < keptBefore.size && line == keptBefore[j]) j++
        assertEquals("lines outside $lines changed", keptBefore.size, j)
    }

    fun lineOf(text: String, needle: String): Int {
        val at = text.indexOf(needle)
        require(at >= 0) { "no '$needle'" }
        return text.substring(0, at).count { it == '\n' } + 1
    }
}
