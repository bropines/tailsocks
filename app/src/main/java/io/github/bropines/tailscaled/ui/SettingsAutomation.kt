package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminApiActivity
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.core.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// J. Automation & API
@Composable
internal fun SettingsAutomationPage(env: SettingsPageEnv) {
    with(env) {
        // The admin console's active profile, for the row that opens it. Read when this page
        // opens and off the main thread: the first read migrates the old plain-text
        // credentials into the Keystore-sealed vault, which no other page of Settings needs.
        // No secret is ever read here, let alone shown.
        val adminInPreview = androidx.compose.ui.platform.LocalInspectionMode.current
        val adminSummary by produceState("") {
            if (adminInPreview) return@produceState
            value = withContext(Dispatchers.IO) {
                runCatching {
                    AdminProfiles.store(context).active()?.let { p ->
                        listOfNotNull(
                            p.displayName,
                            context.getString(if (p.authType == AuthType.OAUTH_CLIENT) R.string.admin_setup_tab_oauth else R.string.admin_setup_tab_token),
                            context.getString(R.string.admin2_profile_read_only_badge).takeIf { p.readOnly },
                        ).joinToString(" · ")
                    }
                }.getOrNull() ?: context.getString(R.string.admin2_settings_none)
            }
        }
        var automationEnabled by remember { mutableStateOf(GlobalSettings.isAutomationEnabled(context)) }
        var automationSecret by remember { mutableStateOf(GlobalSettings.getAutomationSecret(context)) }

        SettingsCard(title = stringResource(R.string.settings_automation_title)) {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_automation_enable_title),
                subtitle = stringResource(R.string.settings_automation_enable_desc),
                icon = Icons.Default.SmartButton,
                checked = automationEnabled
            ) {
                automationEnabled = it
                GlobalSettings.setAutomationEnabled(context, it)
            }

            if (automationEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                 CompactTextField(
                     value = automationSecret,
                     onValueChange = {
                         automationSecret = it
                         GlobalSettings.setAutomationSecret(context, it)
                     },
                     modifier = Modifier.settingAnchor(stringResource(R.string.settings_automation_token_label)),
                     label = stringResource(R.string.settings_automation_token_label),
                     placeholder = stringResource(R.string.settings_automation_token_placeholder),
                     leadingIcon = { Icon(Icons.Default.Key, null) },
                     trailingIcon = {
                         if (automationSecret.isNotEmpty()) {
                             IconButton(onClick = {
                                 automationSecret = ""
                                 GlobalSettings.setAutomationSecret(context, "")
                             }) {
                                 Icon(Icons.Default.Clear, null)
                             }
                         }
                     }
                 )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val token = generateSecureToken(32)
                            automationSecret = token
                            GlobalSettings.setAutomationSecret(context, token)
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Casino, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.settings_automation_generate_token),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (place(Feature.CLIPBOARD_ACTIONS) == Availability.OFFERED) OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_automation_copy_token), automationSecret))
                            // Android 13+ shows its own "Copied" overlay; a Toast would double it.
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, context.getString(R.string.settings_automation_token_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = automationSecret.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.settings_automation_copy_token),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (automationSecret.isEmpty()) stringResource(R.string.settings_automation_no_token) else stringResource(R.string.settings_automation_token_active, automationSecret),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (automationSecret.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }

        if (place(Feature.ADMIN_CONSOLE) == Availability.OFFERED) {
            SettingsCardGap()

            SettingsCard(title = stringResource(R.string.settings_sect_admin_api)) {
                SettingsClickableItem(
                    stringResource(R.string.admin_console_title),
                    adminSummary.ifBlank { stringResource(R.string.settings_link_admin_desc) },
                    Icons.Default.AdminPanelSettings
                ) { context.startActivity(Intent(context, AdminApiActivity::class.java)) }
            }
        }
    }
}
