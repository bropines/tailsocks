package io.github.bropines.tailscaled.admin

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
            text = "Безопасные веб-настройки",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Следующие операции требуют повышенной безопасности и выполняются исключительно через официальную веб-панель управления Tailscale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        WebLinkCard(
            title = "Billing & Plan",
            description = "Управление тарифным планом, просмотр счетов, баланса и платежной информации вашей сети.",
            icon = Icons.Default.CreditCard,
            url = "https://login.tailscale.com/admin/settings/billing",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = "Identity Provider (SSO/IdP)",
            description = "Настройка провайдера аутентификации (Google, Microsoft, GitHub, Okta и др.) для вашей сети.",
            icon = Icons.Default.Security,
            url = "https://login.tailscale.com/admin/settings/identity-provider",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = "Access Control Lists (ACLs)",
            description = "Просмотр и редактирование политик доступа в формате HuJSON для разграничения прав устройств.",
            icon = Icons.Default.Code,
            url = "https://login.tailscale.com/admin/acls",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = "Tailnet Lock",
            description = "Включение сквозного шифрования конфигурации для предотвращения добавления несанкционированных узлов.",
            icon = Icons.Default.Lock,
            url = "https://login.tailscale.com/admin/settings/tailnet-lock",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = "App Integrations (Apps)",
            description = "Управление сторонними интеграциями (Slack, GitHub, VS Code, Heroku) для получения событий вашей сети.",
            icon = Icons.Default.Extension,
            url = "https://login.tailscale.com/admin/settings/apps",
            uriHandler = uriHandler,
            context = context
        )

        WebLinkCard(
            title = "Domain Rename",
            description = "Переименование домена Tailnet (изменение префикса *.ts.net) на уникальное имя.",
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
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
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
