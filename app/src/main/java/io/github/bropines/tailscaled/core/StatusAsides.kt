package io.github.bropines.tailscaled.core

import android.content.Context
import io.github.bropines.tailscaled.R

/**
 * The lines behind a long press on the status card.
 *
 * Some of them count: how often the card has been held, how often the service has been
 * turned on or off, how many times every node has been pinged at once, how many times the
 * account has changed. The counters live in a preference file of their own, nothing but
 * these lines ever reads them, and they leave the device in exactly one way — they do not.
 */
object StatusAsides {
    private const val PREFS = "status_asides"

    const val HOLDS = "holds"
    const val TOGGLES = "toggles"
    const val PINGS = "pings"
    const val SWITCHES = "switches"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Counts one more of [key] and returns the new total. */
    fun bump(context: Context, key: String): Int {
        val next = count(context, key) + 1
        prefs(context).edit().putInt(key, next).apply()
        return next
    }

    fun count(context: Context, key: String): Int = prefs(context).getInt(key, 0)

    /**
     * A line for this press. Round numbers of holds get one of their own; otherwise it is a
     * random pick over the fixed lines and the counting ones, never the same as [previous] —
     * a repeat is what makes a random pick feel broken on a list this short.
     */
    fun pick(context: Context, previous: String?): String {
        val holds = bump(context, HOLDS)
        milestone(context, holds)?.let { return it }
        val pool = context.resources.getStringArray(R.array.status_asides).toList() +
            counting(context, holds)
        return pool.filterNot { it == previous }.randomOrNull() ?: pool.first()
    }

    private fun milestone(context: Context, holds: Int): String? = when (holds) {
        1 -> context.getString(R.string.aside_first)
        10 -> context.getString(R.string.aside_ten)
        42 -> context.getString(R.string.aside_fortytwo)
        100 -> context.getString(R.string.aside_hundred)
        else -> null
    }

    /** The lines that need a number. Each joins the pool only once its number means anything. */
    private fun counting(context: Context, holds: Int): List<String> = buildList {
        add(context.getString(R.string.aside_count_holds, holds))
        count(context, TOGGLES).takeIf { it > 1 }
            ?.let { add(context.getString(R.string.aside_count_toggles, it)) }
        count(context, PINGS).takeIf { it > 0 }
            ?.let { add(context.getString(R.string.aside_count_pings, it)) }
        count(context, SWITCHES).takeIf { it > 0 }
            ?.let { add(context.getString(R.string.aside_count_switches, it)) }
    }
}
