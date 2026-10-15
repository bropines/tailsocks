package io.github.bropines.tailscaled.admin.policy.templates

/*
 * The `when` of a fragment, a block or a suggestion: names joined by `!`, `&&`, `||` and
 * parentheses, `!` binding closest and `&&` before `||`. A name is a fact the engine knows —
 * `param:<id>` (set: a switch on, a list not empty), `param:<id>=<value>` (one of its items is
 * that value), `check:<id>` (the check holds), `headscale` and `tailscale` (the backend),
 * `style:acls` and `style:grants` (the syntax new access rules are written in). Nothing else
 * is evaluated: a condition is a question, never code.
 */

sealed class Condition {
    data class Name(val name: String) : Condition()
    data class Not(val of: Condition) : Condition()
    data class And(val a: Condition, val b: Condition) : Condition()
    data class Or(val a: Condition, val b: Condition) : Condition()

    /** True when [fact] says so for the names it is built of. */
    fun eval(fact: (String) -> Boolean): Boolean = when (this) {
        is Name -> fact(name)
        is Not -> !of.eval(fact)
        is And -> a.eval(fact) && b.eval(fact)
        is Or -> a.eval(fact) || b.eval(fact)
    }

    /** Every name it asks about. */
    val names: List<String>
        get() = when (this) {
            is Name -> listOf(name)
            is Not -> of.names
            is And -> a.names + b.names
            is Or -> a.names + b.names
        }
}

class ConditionException(message: String) : IllegalArgumentException(message)

object Conditions {

    private val NAME = Regex("""[a-z]+(:[A-Za-z0-9_.@*/:=+-]+)?""")
    private const val MAX_LENGTH = 300

    fun parse(text: String): Condition {
        if (text.length > MAX_LENGTH) throw ConditionException("condition longer than $MAX_LENGTH characters")
        val p = Parser(tokens(text))
        val c = p.or()
        if (!p.done) throw ConditionException("unexpected \"${p.peek()}\" in \"$text\"")
        return c
    }

    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '(' || c == ')' || c == '!' -> { out += c.toString(); i++ }
                text.startsWith("&&", i) || text.startsWith("||", i) -> { out += text.substring(i, i + 2); i += 2 }
                else -> {
                    val m = NAME.matchAt(text, i) ?: throw ConditionException("cannot read \"${text.substring(i).take(20)}\"")
                    out += m.value
                    i = m.range.last + 1
                }
            }
        }
        if (out.isEmpty()) throw ConditionException("empty condition")
        return out
    }

    private class Parser(private val t: List<String>) {
        private var pos = 0
        val done: Boolean get() = pos == t.size
        fun peek(): String? = t.getOrNull(pos)

        fun or(): Condition {
            var c = and()
            while (peek() == "||") { pos++; c = Condition.Or(c, and()) }
            return c
        }

        private fun and(): Condition {
            var c = unary()
            while (peek() == "&&") { pos++; c = Condition.And(c, unary()) }
            return c
        }

        private fun unary(): Condition = when (val x = peek()) {
            "!" -> { pos++; Condition.Not(unary()) }
            "(" -> {
                pos++
                val c = or()
                if (peek() != ")") throw ConditionException("a \"(\" is not closed")
                pos++
                c
            }
            null, ")", "&&", "||" -> throw ConditionException("a name is missing before \"${x ?: "the end"}\"")
            else -> { pos++; Condition.Name(x) }
        }
    }
}
