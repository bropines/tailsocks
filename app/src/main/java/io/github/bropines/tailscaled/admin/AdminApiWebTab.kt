package io.github.bropines.tailscaled.admin

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AdminApiWebTabContent() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.admin_web_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.admin_web_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_billing_title),
            description = stringResource(R.string.admin_web_billing_desc),
            icon = Icons.Default.CreditCard,
            url = "https://login.tailscale.com/admin/settings/billing",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_idp_title),
            description = stringResource(R.string.admin_web_idp_desc),
            icon = Icons.Default.Security,
            url = "https://login.tailscale.com/admin/settings/identity-provider",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_acl_title),
            description = stringResource(R.string.admin_web_acl_desc),
            icon = Icons.Default.Code,
            url = "https://login.tailscale.com/admin/acls",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_lock_title),
            description = stringResource(R.string.admin_web_lock_desc),
            icon = Icons.Default.Lock,
            url = "https://login.tailscale.com/admin/settings/tailnet-lock",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_apps_title),
            description = stringResource(R.string.admin_web_apps_desc),
            icon = Icons.Default.Extension,
            url = "https://login.tailscale.com/admin/settings/apps",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = stringResource(R.string.admin_web_domain_title),
            description = stringResource(R.string.admin_web_domain_desc),
            icon = Icons.Default.SettingsEthernet,
            url = "https://login.tailscale.com/admin/settings/general",
            uriHandler = uriHandler,
            context = context
        )
    }
}

@Composable
fun WebLinkCard(
    title: String,
    description: String,
    icon: ImageVector,
    url: String,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    context: android.content.Context
) {
    val cannotOpenBrowser = stringResource(R.string.cannot_open_browser)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {
                    Toast.makeText(context, cannotOpenBrowser, Toast.LENGTH_SHORT).show()
                }
            },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
