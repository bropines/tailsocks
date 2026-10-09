package io.github.bropines.tailscaled.admin.headscale

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminCredential
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleV1Backend
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.admin.profile.BaseUrlRules
import io.github.bropines.tailscaled.admin.secure.SecretField
import io.github.bropines.tailscaled.ui.HelpText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The profile editor's Headscale side: switching the backend, and what a Headscale profile asks for. */
object HeadscaleEditor {

    fun isHeadscale(d: ProfileDraft): Boolean = d.backend != BackendKind.TAILSCALE

    /**
     * Tailscale ↔ Headscale. The typed secret goes (it belongs to the other kind of server), the
     * address goes to the other's default — empty for Headscale, which has none — and the
     * credential becomes the kind that server takes.
     */
    fun switchBackend(d: ProfileDraft, toHeadscale: Boolean): ProfileDraft = when {
        toHeadscale == isHeadscale(d) -> d
        toHeadscale -> d.copy(
            backend = BackendKind.HEADSCALE_V1,
            authType = AuthType.HEADSCALE_KEY,
            baseUrl = if (BaseUrlRules.isTailscaleCloud(d.baseUrl)) "" else d.baseUrl,
            tailnet = "-",
            secret = "",
        )
        else -> d.copy(backend = BackendKind.TAILSCALE, authType = AuthType.API_TOKEN, baseUrl = TailscaleBackend.DEFAULT_BASE_URL, secret = "")
    }
}

/** Server address, API key, and the plain statement that the key itself has no read-only mode. */
@Composable
fun HeadscaleEditorFields(
    draft: ProfileDraft,
    onChange: ((ProfileDraft) -> ProfileDraft) -> Unit,
    keepPlaceholder: String?,
    showLabel: String,
    hideLabel: String,
) {
    val ctx = LocalContext.current
    when (draft.backend) {
        BackendKind.HEADSCALE_V1 -> if (draft.id != null) HelpText(ctx.getString(R.string.admin_hs_editor_found_v1))
        BackendKind.HEADSCALE_V2 -> HelpText(ctx.getString(R.string.admin_hs_editor_found_v2))
        else -> Unit
    }
    OutlinedTextField(
        value = draft.baseUrl,
        onValueChange = { v -> onChange { it.copy(baseUrl = v.trim()) } },
        label = { Text(ctx.getString(R.string.admin_hs_editor_url)) },
        placeholder = { Text("https://headscale.example.com") },
        supportingText = { Text(ctx.getString(R.string.admin_hs_editor_url_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField(
        value = draft.secret,
        onValueChange = { v -> onChange { it.copy(secret = v.trim()) } },
        label = ctx.getString(R.string.admin_hs_editor_key),
        placeholder = keepPlaceholder ?: "hskey-api-…",
        showLabel = showLabel, hideLabel = hideLabel,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(ctx.getString(R.string.admin_hs_editor_key_help))
    HelpText(ctx.getString(R.string.admin_hs_all_access), color = MaterialTheme.colorScheme.error)
}

/**
 * Before a Headscale profile is saved: which API the server has (`/version`, probes), so the
 * profile gets the backend that fits. A key the server refuses stops the save; a server that
 * cannot be reached stops it too, unless the person saves anyway — then the kind already set stays.
 */
object HeadscaleProfiles {
    sealed class Detection {
        data class Ok(val profile: AdminProfile) : Detection()
        data class Failed(val message: String, val offerUnchecked: Boolean) : Detection()
    }

    suspend fun withDetectedBackend(app: Context, ctx: Context, profile: AdminProfile, newSecret: String, proxyPassword: String?, unchecked: Boolean): Detection {
        if (profile.backend == BackendKind.TAILSCALE) return Detection.Ok(profile)
        if (BaseUrlRules.isTailscaleCloud(profile.baseUrl)) return Detection.Failed(ctx.getString(R.string.admin_hs_editor_tailscale_url), false)
        return try {
            val backend = withContext(Dispatchers.IO) {
                val credential = if (newSecret.isNotEmpty()) AdminCredential.HeadscaleKey(newSecret) else AdminProfiles.credential(app, profile)
                AdminProfiles.newBackend(app, profile.copy(backend = BackendKind.HEADSCALE_V1), credential, proxyPassword)
            }
            val (kind, _) = (backend as HeadscaleV1Backend).detectServer()
            Detection.Ok(profile.copy(backend = kind))
        } catch (e: AdminApiException.Unauthorized) {
            Detection.Failed(ctx.getString(R.string.admin2_profile_check_failed, ConsoleText.error(ctx, e)), false)
        } catch (e: Exception) {
            if (unchecked) Detection.Ok(profile)
            else Detection.Failed(ctx.getString(R.string.admin2_profile_check_failed, ConsoleText.error(ctx, e)), true)
        }
    }
}
