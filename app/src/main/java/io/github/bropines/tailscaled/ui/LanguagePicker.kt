package io.github.bropines.tailscaled.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppLanguages
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.ui.theme.findActivity

/**
 * The app's language: one row naming the current choice, and a sheet with the system's
 * language and every one in [AppLanguages]. A row rather than chips: chips held three, and
 * Weblate keeps adding languages. Each one is named in its own language, with its name in
 * the current one under it.
 *
 * The pick is applied when the sheet has slid away: it recreates the screen, which would
 * otherwise cut the sheet off mid-slide.
 */
@Composable
internal fun LanguagePicker(modifier: Modifier = Modifier, height: Dp = 46.dp) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GlobalSettings.getString(context, "app_locale", AppLanguages.SYSTEM)) }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val systemLabel = stringResource(R.string.settings_lang_sys)
    val title = stringResource(R.string.settings_lang_title)

    val shown = if (current == AppLanguages.SYSTEM || current !in AppLanguages.TAGS) systemLabel else AppLanguages.nativeName(current)
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .tvFocusRing(source, shape)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button) { picking = true }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Language, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (picking) {
        // Resolved here: the sheet is a window of its own — see wrapContextWithLocale().
        PickerSheet(
            title = title,
            options = languageOptions(),
            selected = if (current in AppLanguages.TAGS) current else AppLanguages.SYSTEM,
            onPick = { if (it != current) picked = it },
            onDismiss = {
                picking = false
                picked?.let { tag ->
                    picked = null
                    current = tag
                    applyAppLanguage(context, tag)
                }
            }
        )
    }
}

/** The sheet's rows: the system's language, then each of [AppLanguages], in the composition's language. */
@Composable
internal fun languageOptions(): List<PickerOption<String>> {
    val reader = LocalConfiguration.current.locales[0]
    val systemLabel = stringResource(R.string.settings_lang_sys)
    return buildList {
        add(PickerOption(AppLanguages.SYSTEM, systemLabel, Icons.Default.PhoneAndroid))
        AppLanguages.TAGS.forEach { tag ->
            add(PickerOption(tag, AppLanguages.nativeName(tag), Icons.Default.Translate, supporting = AppLanguages.nameFor(tag, reader)))
        }
    }
}

/** Stores [tag] as `app_locale`, hands it to the framework and recreates the screen. */
internal fun applyAppLanguage(context: android.content.Context, tag: String) {
    GlobalSettings.setString(context, "app_locale", tag)
    val locales = if (tag == AppLanguages.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    AppCompatDelegate.setApplicationLocales(locales)
    context.findActivity()?.recreate()
}
