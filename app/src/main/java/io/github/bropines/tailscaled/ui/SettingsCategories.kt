package io.github.bropines.tailscaled.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Feature

/*
 * What Settings is made of, as tables: the categories of the hub and the pages behind them,
 * the pages a device that keeps features for its advanced settings lists on the Advanced page
 * (with the screens of their own it links to there), and which page a named section lands on.
 * The screen that draws them is SettingsScreen in ui/SettingsActivity.kt; SettingsCatalogTest
 * reads this file to hold the table to the catalogue (ui/SettingsCatalog.kt).
 */

/**
 * One row of the settings hub. [id] is what `openSection` stores, so it is a
 * stable string and must survive a `recreate()` — never reorder-sensitive.
 */
internal data class SettingsCategory(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /**
     * How many cards the section's page holds, which decides whether a tablet
     * stands them in columns: from two beside the categories, from three on a
     * medium window showing the page alone. One block — a card, or the tunnel
     * modes with what each one opens — stays a single readable column.
     */
    val cards: Int = 1,
    /** One long card whose rows split into two columns instead (see [LocalSettingsColumns]). */
    val splits: Boolean = false,
    /**
     * The feature the whole page is, where it is one (core/DeviceProfile.kt). A device that
     * is offered it finds the page in the hub; one that keeps it for the advanced settings —
     * a television — finds it on the Advanced page instead.
     */
    val feature: Feature? = null,
)

/** The 11 categories of the target structure, in their A..K order. */
internal val settingsCategories = listOf(
    SettingsCategory("appearance", R.string.settings_cat_appearance, R.string.settings_cat_appearance_desc, Icons.Default.Palette, cards = 2, splits = true),
    SettingsCategory("account", R.string.settings_cat_account, R.string.settings_cat_account_desc, Icons.Default.AccountCircle, cards = 3),
    SettingsCategory("tunnel", R.string.settings_cat_tunnel, R.string.settings_cat_tunnel_desc, Icons.Default.VpnLock),
    SettingsCategory("proxies", R.string.settings_cat_proxies, R.string.settings_cat_proxies_desc, Icons.Default.Lan, cards = 3),
    SettingsCategory("dns", R.string.settings_cat_dns, R.string.settings_cat_dns_desc, Icons.Default.Dns, cards = 3),
    SettingsCategory("bypass", R.string.settings_cat_bypass, R.string.settings_cat_bypass_desc, Icons.Default.Shield, cards = 3),
    SettingsCategory("sharing", R.string.settings_cat_sharing, R.string.settings_cat_sharing_desc, Icons.Default.Share, cards = 3),
    SettingsCategory("background", R.string.settings_cat_background, R.string.settings_cat_background_desc, Icons.Default.Bolt),
    SettingsCategory("backup", R.string.settings_cat_backup, R.string.settings_cat_backup_desc, Icons.Default.Backup, feature = Feature.BACKUP_RESTORE),
    SettingsCategory("automation", R.string.settings_cat_automation, R.string.settings_cat_automation_desc, Icons.Default.SmartButton, cards = 2, feature = Feature.AUTOMATION),
    SettingsCategory("diagnostics", R.string.settings_cat_diagnostics, R.string.settings_cat_diagnostics_desc, Icons.Default.BugReport, cards = 4, feature = Feature.DIAGNOSTICS)
)

/**
 * The hub's last row and the page behind it. On a device that keeps some features for its
 * advanced settings — a television — the page lists the pages and the screens of those
 * features, and under them what is still an experiment. A handheld is offered everything
 * else in its usual place, so there the experiments are the whole page
 * ([experimentalCategory]).
 */
internal val advancedCategory =
    SettingsCategory("advanced", R.string.tv_settings_advanced, R.string.tv_settings_advanced_desc, Icons.Default.Tune, cards = 2)

/** [advancedCategory] on a handheld: the same row and page, said for the two cards they hold there. */
internal val experimentalCategory =
    advancedCategory.copy(descRes = R.string.exposure_advanced_desc, cards = 2)

/**
 * Two parts of a category that are features of their own, as pages for the Advanced page:
 * the theme and the icon out of Appearance, and Tunnel mode with Root among its modes.
 */
internal val themePage =
    SettingsCategory("theme", R.string.tv_settings_theme, R.string.tv_settings_theme_desc, Icons.Default.Palette, feature = Feature.THEME_AND_ICON)
internal val rootPage =
    SettingsCategory("root", R.string.tv_settings_root, R.string.tv_settings_root_desc, Icons.Default.Security, feature = Feature.ROOT_MODE)

/** Every page Settings can show, for a page's title and layout. */
internal val settingsPages = settingsCategories + themePage + rootPage + advancedCategory

/** The pages the Advanced page may list, in its order: [SettingsCategory.feature] decides which it does. */
internal val advancedPageCandidates = listOf(themePage, rootPage) + settingsCategories.filter { it.feature != null }

/** A screen of its own that the Advanced page links to where its [feature] is kept for the advanced settings. */
internal class AdvancedTool(val feature: Feature, val titleRes: Int, val descRes: Int, val icon: ImageVector, val screen: Class<*>)

internal val advancedTools = listOf(
    AdvancedTool(Feature.LOGS, R.string.logs_title, R.string.settings_link_logs_desc, Icons.AutoMirrored.Filled.List, LogsActivity::class.java),
    AdvancedTool(Feature.CONSOLE, R.string.console_title, R.string.settings_link_console_desc, Icons.Default.Terminal, ConsoleActivity::class.java),
    AdvancedTool(Feature.DNS_SCREEN, R.string.dns_title, R.string.settings_link_dns_desc, Icons.Default.Troubleshoot, DnsActivity::class.java),
    // The screen itself; what may be changed on it is the screen's own question.
    AdvancedTool(Feature.SERVE_EDIT, R.string.serve_title, R.string.settings_link_serve_desc, Icons.Default.Public, ServeActivity::class.java),
)

/**
 * The page [section] names, as a device has it: [hub] are the pages its list shows and
 * [advanced] the ones its Advanced page does. A link (ui/DeepLinks.kt) or a caller may name
 * any page Settings knows. A device that keeps the theme inside Appearance and Root among
 * the tunnel modes — a handheld — opens those for their names; a name it has no page for
 * opens the list.
 */
internal fun settingsLanding(section: String?, hub: List<String>, advanced: List<String>): String? = when (section) {
    null -> null
    in hub, in advanced -> section
    themePage.id -> "appearance".takeIf { it in hub }
    rootPage.id -> "tunnel".takeIf { it in hub }
    else -> null
}
