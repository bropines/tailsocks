package io.github.bropines.tailscaled.admin.policy

import java.util.concurrent.atomic.AtomicInteger

/**
 * The last few results of a pure function of a policy text, keyed by the exact text.
 *
 * One visual edit used to parse the same two texts (before and after) eight to ten times, on
 * the main thread: the draft's model, each primitive of a composite edit, the check after it,
 * the shell's issue and risks, every page's own `remember(text) { parse }`. Each of those asks
 * for the tree of a text it was handed; this keeps the answer for the last [capacity] texts so
 * the second and later asks are free.
 *
 * Correct by construction: a hit needs the very same characters (`===`, else length, hash and
 * `==`), and the value is a function of the text alone — the trees are immutable (`val`s all
 * the way down) — so a hit can never hand back a stale or foreign tree. Small on purpose: a
 * 200 KB policy and its tree are not pinned beyond the texts the editor is working on.
 *
 * Thread-safe: the risks run on Dispatchers.Default while the main thread edits. The value is
 * computed outside the lock (two threads may both compute it once; both answers are equal).
 */
internal class TextMemo<V : Any>(private val capacity: Int = 2) {
    private val keys = arrayOfNulls<String>(capacity)
    private val values = arrayOfNulls<Any>(capacity)

    /** How many times [getOrPut] had to compute: what the tests count parses by. */
    val misses = AtomicInteger()

    /** A test's look at each text that had to be computed; null in the app. */
    @Volatile
    var onMiss: ((String) -> Unit)? = null

    fun getOrPut(text: String, compute: () -> V): V {
        get(text)?.let { return it }
        misses.incrementAndGet()
        onMiss?.invoke(text)
        val v = compute()
        put(text, v)
        return v
    }

    @Synchronized
    private fun get(text: String): V? {
        for (i in 0 until capacity) {
            val k = keys[i] ?: continue
            if (k === text || (k.length == text.length && k.hashCode() == text.hashCode() && k == text)) {
                @Suppress("UNCHECKED_CAST")
                val v = values[i] as V
                // Most recent first: the slot to drop is always the last.
                moveToFront(i, k, v)
                return v
            }
        }
        return null
    }

    @Synchronized
    private fun put(text: String, v: V) {
        val i = keys.indexOfFirst { it == text }.takeIf { it >= 0 } ?: (capacity - 1)
        moveToFront(i, text, v)
    }

    private fun moveToFront(i: Int, k: String, v: Any) {
        for (j in i downTo 1) {
            keys[j] = keys[j - 1]
            values[j] = values[j - 1]
        }
        keys[0] = k
        values[0] = v
    }

    @Synchronized
    fun clear() {
        keys.fill(null)
        values.fill(null)
    }
}
