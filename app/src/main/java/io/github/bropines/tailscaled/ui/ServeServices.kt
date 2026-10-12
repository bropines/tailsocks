package io.github.bropines.tailscaled.ui

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Where each Tailscale Service this node serves stands in the tailnet, for the Serve screen's
 * service headings. A rule under `svc:<name>` in the serve config makes the daemon advertise
 * the service (ipnlocal's vipServicesFromPrefsLocked marks every configured service Active);
 * the tailnet answers on it only once the service is defined there and this node is approved
 * as its host — by hand in the admin console, or by an auto-approval rule in the policy. When
 * that has happened the netmap carries the node's `service-host` capability with the
 * service's addresses. Before then, only the Admin API can tell "not defined" from "waiting
 * for approval", and only when a profile serves this tailnet.
 */

/** The admin console's Services tab: where a service is defined and its hosts approved. */
internal const val SERVICES_CONSOLE_URL = "https://login.tailscale.com/admin/services"

/**
 * What the Admin API said about the tailnet's services: the names it defines (without
 * `svc:`), and for each service this node serves that is defined, this node's approval level
 * as a host — "approved:auto", "approved:manual", "not-approved" — or null when the node is
 * not listed as one yet. A service whose hosts could not be read is absent from [approval].
 */
data class TailnetServices(
    val defined: Set<String> = emptySet(),
    val approval: Map<String, String?> = emptyMap(),
)

enum class ServiceStanding {
    /** The netmap gives this node the service's address: it answers. */
    SERVING,
    /** Defined and this node approved; the address has not reached the netmap yet. */
    APPROVED,
    /** Defined; this node is not approved as a host (or not listed as one yet). */
    AWAITING_APPROVAL,
    /** The tailnet has no service by this name. */
    NOT_DEFINED,
    /** Not serving, and nothing to tell why: no admin profile, or it did not answer. */
    UNKNOWN,
}

/** A service's standing, the address it answers on while [ServiceStanding.SERVING], and
 *  whether this node's approval came from an auto-approval rule. */
data class ServiceState(val standing: ServiceStanding, val address: String? = null, val auto: Boolean = false)

/**
 * [name]'s standing. The netmap's `service-host` ([hosted], by name without `svc:`) is the
 * fact; the Admin API's view ([tailnet], null without one) only explains its absence.
 */
internal fun serviceStateOf(name: String, hosted: Map<String, List<String>>, tailnet: TailnetServices?): ServiceState {
    val level = tailnet?.approval?.get(name)
    val auto = level == "approved:auto"
    hosted[name]?.let { addrs ->
        // The IPv4 address when there is one: it is what a person types.
        return ServiceState(ServiceStanding.SERVING, addrs.firstOrNull { ':' !in it } ?: addrs.firstOrNull(), auto)
    }
    return when {
        tailnet == null -> ServiceState(ServiceStanding.UNKNOWN)
        name !in tailnet.defined -> ServiceState(ServiceStanding.NOT_DEFINED)
        name !in tailnet.approval -> ServiceState(ServiceStanding.UNKNOWN)
        level?.startsWith("approved") == true -> ServiceState(ServiceStanding.APPROVED, auto = auto)
        else -> ServiceState(ServiceStanding.AWAITING_APPROVAL)
    }
}

/** A service name as the daemon and the console take it: lower case, no `svc:` in front. */
internal fun normalizeServiceName(typed: String): String = typed.trim().lowercase().removePrefix("svc:")

private val SERVICE_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/** Whether [name] (already normalized) can be a service: one DNS label. */
internal fun isServiceName(name: String): Boolean = SERVICE_LABEL.matches(name)

/**
 * The names worth offering under the editor's service field, in the order [known] gives them —
 * this node's own services first, then the ones it hosts, then the rest of the tailnet's —
 * narrowed to those containing what is typed, without the one typed exactly, at most [limit].
 */
internal fun serviceNameSuggestions(typed: String, known: List<String>, limit: Int = 6): List<String> {
    val t = normalizeServiceName(typed)
    return known.asSequence()
        .map(::normalizeServiceName)
        .filter { isServiceName(it) && it != t && (t.isEmpty() || t in it) }
        .distinct()
        .take(limit)
        .toList()
}

/**
 * Reads [TailnetServices] through [profile], read-only, so it needs no unlock: the tailnet's
 * services, and this node's ([nodeId], its stable ID) approval for each of [names] that is
 * defined. Null when the backend has no services (Headscale), or the API did not answer —
 * never an error to show: the headings fall back to what the netmap says.
 */
internal object TailnetServicesSource {
    private const val TAG = "TailnetServices"

    suspend fun load(context: Context, profile: AdminProfile, nodeId: String, names: Collection<String>): TailnetServices? =
        withContext(Dispatchers.IO) {
            try {
                val backend = AdminProfiles.newBackend(context, profile)
                if (BackendFeature.SERVICES !in backend.capabilities.value.features) return@withContext null
                val defined = backend.listServices().items.map { normalizeServiceName(it.name) }.filter { it.isNotEmpty() }.toSet()
                val approval = HashMap<String, String?>()
                for (name in names.filter { it in defined }) {
                    try {
                        val hosts = backend.serviceHosts("svc:$name").items
                        approval[name] = hosts.firstOrNull { it.stableNodeID == nodeId }?.approvalLevel
                    } catch (e: Exception) {
                        Log.w(TAG, "Hosts of svc:$name: ${e.javaClass.simpleName}")
                    }
                }
                TailnetServices(defined, approval)
            } catch (e: Exception) {
                Log.w(TAG, "Services of ${profile.displayName}: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
}
