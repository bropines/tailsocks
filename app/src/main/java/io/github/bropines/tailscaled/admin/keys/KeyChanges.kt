package io.github.bropines.tailscaled.admin.keys

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.api.OAuthClientRequest
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * The Keys tab's writes as [PlannedChange]s: a new auth key, a new OAuth client, a revoke.
 * Each says what the key will be or what stops working with it, in the language of the
 * Context it is built with.
 */
object KeyChanges {

    /** [expiry] is the chosen lifetime in words ("7 days"), for the summary. */
    fun createAuthKey(ctx: Context, request: AuthKeyRequest, expiry: String, onCreated: (ApiKey) -> Unit): PlannedChange {
        var created: ApiKey? = null
        val traits = buildList {
            add(ctx.getString(if (request.reusable) R.string.admin2_key_reusable else R.string.admin_k_single_use))
            if (request.ephemeral) add(ctx.getString(R.string.admin_k_ephemeral))
            if (request.preauthorized) add(ctx.getString(R.string.admin_k_preauthorized))
            addAll(request.tags)
        }
        val warnings = buildList {
            if (request.reusable && request.preauthorized) add(ctx.getString(R.string.admin_k_warn_reusable_preauth))
            if (request.tags.isEmpty()) add(ctx.getString(R.string.admin_k_warn_untagged))
        }
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.KEY_CREATE,
                changeClass = ChangeClassifier.classify(ChangeKind.KEY_CREATE),
                target = ChangeTarget(TargetType.KEY, "new", request.description.ifBlank { ctx.getString(R.string.admin2_key_type_auth) }),
                title = ctx.getString(R.string.admin2_change_key_create),
                effect = ctx.getString(R.string.admin_k_create_auth_effect, traits.joinToString(" · "), expiry),
                warnings = warnings,
            ),
            apply = { b -> b.createAuthKey(request).also { created = it }.let(onCreated) },
            verify = verify@{ b ->
                val id = created?.id?.takeIf { it.isNotBlank() } ?: return@verify false
                val o = b.getKey(id).createOptions
                o != null && o.reusable == request.reusable && o.ephemeral == request.ephemeral &&
                    o.preauthorized == request.preauthorized && o.tags.orEmpty().toSet() == request.tags.toSet()
            },
        )
    }

    fun createOAuthClient(ctx: Context, request: OAuthClientRequest, onCreated: (ApiKey) -> Unit): PlannedChange {
        var created: ApiKey? = null
        val writes = request.scopes.filterNot { it.endsWith(":read") }
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.OAUTH_CLIENT_CREATE,
                changeClass = ChangeClassifier.oauthClient(request.scopes),
                target = ChangeTarget(TargetType.KEY, "new", request.description.trim()),
                title = ctx.getString(R.string.admin_k_change_client_create),
                effect = ctx.getString(R.string.admin_k_change_client_create_effect),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_k_scopes), null, ConsoleText.list(request.scopes)),
                    request.tags.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin2_diff_tags), null, ConsoleText.list(it)) },
                ),
                warnings = buildList {
                    if (writes.isNotEmpty()) add(ctx.getString(R.string.admin_k_warn_client_writes, writes.joinToString(", ")))
                    add(ctx.getString(R.string.admin_k_warn_client_never_expires))
                },
            ),
            apply = { b -> b.createOAuthClient(request).also { created = it }.let(onCreated) },
            verify = verify@{ b ->
                val id = created?.id?.takeIf { it.isNotBlank() } ?: return@verify false
                b.getKey(id).scopes.toSet() == request.scopes.toSet()
            },
        )
    }

    /**
     * Revoking [k], with what depends on it said out loud: CI that adds devices with a reusable
     * key, integrations on an OAuth client, scripts on a token. Typed back: its description,
     * or its id when it has none.
     */
    fun revoke(ctx: Context, k: ApiKey): PlannedChange {
        val name = k.description?.takeIf { it.isNotBlank() } ?: k.id
        val warnings = buildList {
            when (k.type) {
                KeyType.AUTH -> if (k.createOptions?.reusable == true) add(ctx.getString(R.string.admin_k_warn_revoke_reusable))
                KeyType.CLIENT -> add(ctx.getString(R.string.admin_k_warn_revoke_client))
                KeyType.FEDERATED -> add(ctx.getString(R.string.admin_k_warn_revoke_federated))
                KeyType.API -> add(ctx.getString(R.string.admin_k_warn_revoke_api))
                KeyType.UNKNOWN -> Unit
            }
        }
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.KEY_REVOKE,
                changeClass = ChangeClassifier.classify(ChangeKind.KEY_REVOKE),
                target = ConsoleChanges.keyTarget(k),
                title = ctx.getString(R.string.admin2_change_key_revoke, name),
                effect = ctx.getString(
                    if (k.type == KeyType.AUTH) R.string.admin2_change_key_revoke_effect else R.string.admin_k_revoke_effect_credential
                ),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_key), "${ConsoleText.keyType(ctx, k.type)} ${k.id}", null)),
                warnings = warnings,
                area = KeyList.revokeArea(k.type),
            ),
            apply = { it.deleteKey(k.id) },
            verify = { b ->
                val after = runCatching { b.getKey(k.id) }
                after.exceptionOrNull() is AdminApiException.NotFound || after.getOrNull()?.let { it.isRevoked || it.invalid == true } == true
            },
        )
    }
}
