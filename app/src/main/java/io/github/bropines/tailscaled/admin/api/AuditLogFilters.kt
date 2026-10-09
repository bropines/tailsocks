package io.github.bropines.tailscaled.admin.api

/**
 * What the configuration audit log is narrowed to on the server. Each list is OR-ed within
 * itself and the three are AND-ed, as the API documents:
 *  - [actors]: exact actor ids, or `~text` for a wildcard search on login or display name;
 *  - [targets]: matched against any part of any target;
 *  - [events]: `TARGET.ACTION[.PROPERTY]` names, "NODE.UPDATE.MACHINE_NAME".
 */
data class AuditLogFilters(
    val actors: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val events: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = actors.isEmpty() && targets.isEmpty() && events.isEmpty()

    /** The query parameters, one per value (`actor=a&actor=b`), blanks dropped. */
    fun query(): List<Pair<String, String>> =
        actors.clean().map { "actor" to it } + targets.clean().map { "target" to it } + events.clean().map { "event" to it }

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        /**
         * The actor filter for what a person typed: an id as it is, anything else as the
         * wildcard search (`~bob`), which is what someone typing a name means.
         */
        fun actor(text: String): String {
            val t = text.trim()
            return when {
                t.isEmpty() || t.startsWith("~") -> t
                ID.matches(t) -> t
                else -> "~$t"
            }
        }

        /** "uZKk3KSfrH11CNTRL", "nBLYviWLGB21DEVEL": Tailscale's stable ids. */
        private val ID = Regex("^[un][A-Za-z0-9]{8,}(CNTRL|DEVEL)$")
    }
}
