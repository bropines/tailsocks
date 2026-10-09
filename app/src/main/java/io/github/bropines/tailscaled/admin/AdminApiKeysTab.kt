package io.github.bropines.tailscaled.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.keys.KeysList

/**
 * The key list under its old name, for callers from before the Keys tab (the screenshot
 * previews). The tab itself is [io.github.bropines.tailscaled.admin.keys.KeysTab].
 */
@Composable
fun KeysTabContent(
    state: Loadable<List<ApiKey>>,
    ownKeyId: String?,
    canWrite: Boolean,
    onRetry: () -> Unit,
    onRevokeClick: (ApiKey) -> Unit,
    onCreateKeyClick: () -> Unit,
) = KeysList(
    keys = state,
    ownKeyId = ownKeyId,
    now = remember { System.currentTimeMillis() },
    canRevoke = { canWrite },
    onRetry = onRetry,
    onRevoke = onRevokeClick,
    onCreate = onCreateKeyClick.takeIf { canWrite },
)
