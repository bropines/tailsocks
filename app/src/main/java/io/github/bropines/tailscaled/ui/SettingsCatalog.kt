package io.github.bropines.tailscaled.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Availability
import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature

/*
 * Everything Settings holds, as the search knows it: every page, and every row, switch,
 * choice and action on a page — what it is called, what it does in one line, the words
 * people look for it by, where it lives, and when it is there at all.
 *
 * A row is anchored by the title it shows: the rows of the settings family mark themselves
 * with it (Modifier.settingAnchor, ui/SettingsSearchUi.kt), so an entry here needs nothing
 * added to the page to be opened on its row.
 *
 * The catalogue is kept whole by a test, not by memory (SettingsCatalogTest): a title that
 * a settings source hands to a row and that has no entry here fails the build, and so does
 * an entry whose row is gone, or one without keywords in English and in Russian. Adding a
 * setting therefore means adding its entry — and its search_kw_ string, twice.
 */

/**
 * A page of Settings, by the id SettingsActivity opens it with ([DeepLinks.SETTINGS_SECTIONS]).
 * [feature] is the feature the whole page is, where it is one: a device that keeps it for
 * the advanced settings has the page under Advanced. [aside] pages exist only there — on
 * every other device their rows are part of another page.
 */
enum class SettingsPage(
    val id: String,
    @param:StringRes val title: Int,
    val icon: ImageVector,
    val feature: Feature? = null,
    val aside: Boolean = false,
) {
    APPEARANCE("appearance", R.string.settings_cat_appearance, Icons.Default.Palette),
    ACCOUNT("account", R.string.settings_cat_account, Icons.Default.AccountCircle),
    TUNNEL("tunnel", R.string.settings_cat_tunnel, Icons.Default.VpnLock),
    PROXIES("proxies", R.string.settings_cat_proxies, Icons.Default.Lan),
    DNS("dns", R.string.settings_cat_dns, Icons.Default.Dns),
    BYPASS("bypass", R.string.settings_cat_bypass, Icons.Default.Shield),
    SHARING("sharing", R.string.settings_cat_sharing, Icons.Default.Share),
    BACKGROUND("background", R.string.settings_cat_background, Icons.Default.Bolt),
    BACKUP("backup", R.string.settings_cat_backup, Icons.Default.Backup, Feature.BACKUP_RESTORE),
    AUTOMATION("automation", R.string.settings_cat_automation, Icons.Default.SmartButton, Feature.AUTOMATION),
    DIAGNOSTICS("diagnostics", R.string.settings_cat_diagnostics, Icons.Default.BugReport, Feature.DIAGNOSTICS),
    ADVANCED("advanced", R.string.tv_settings_advanced, Icons.Default.Tune),
    THEME("theme", R.string.tv_settings_theme, Icons.Default.Palette, Feature.THEME_AND_ICON, aside = true),
    ROOT("root", R.string.tv_settings_root, Icons.Default.Security, Feature.ROOT_MODE, aside = true),
}

/**
 * What has to be so for a row to be on its page. While it is not, a result still finds the
 * setting, says [reason], and opens the row that brings it ([broughtBy], an entry's id).
 * Without a [reason] there is nothing the user can do about it here: the entry is not offered.
 */
enum class Need(@param:StringRes val reason: Int = 0, val broughtBy: String? = null) {
    TUN_MODE(R.string.search_need_tun, "mode_vpn"),
    TUN_HEV(R.string.search_need_tun_hev, "tun_engine"),
    TUN_NATIVE(R.string.search_need_tun_native, "tun_engine"),
    TUN_OR_ROOT(R.string.search_need_tun_or_root, "mode_vpn"),
    /** VPN mode, or Root Mode with tailscale0: where an exit node has a route table to keep ranges out of. */
    TUN_OR_ROOT_TUN(R.string.search_need_tun_or_root_tun, "mode_vpn"),
    ROOT_MODE(R.string.search_need_root, "mode_root"),
    ROOT_TUN(R.string.search_need_root_tun, "root_tun"),
    HTTP_PROXY(R.string.search_need_http_proxy, "http_enable"),
    AUTO_RECONNECT(R.string.search_need_auto_reconnect, "auto_reconnect"),
    WEB_UI(R.string.search_need_web_ui, "web_ui"),
    AUTOMATION(R.string.search_need_automation, "automation_enable"),
    RELAY(R.string.search_need_relay, "relay"),
    TAILNET_STRICT(R.string.search_need_tailnet_strict, "tailnet_strict"),
    /** The palette, which Material You's colours replace. */
    STATIC_COLORS(R.string.search_need_static_colors, "dynamic_color"),
    /** Material You: Android 12 and later. */
    ANDROID_12,
    /** A build that updates itself from GitHub. */
    SELF_UPDATE,
}

/** What is so on this device right now: the answers to [Need]. */
fun interface SettingsFacts {
    fun met(need: Need): Boolean
}

/**
 * One thing the search finds. [anchor] is the title of the row to open on — the entry's own
 * [title] unless the row shows another — and 0 for a page. [summary] is the one line a result
 * shows; [help], where there is more to say, is only searched. [card] is the card the row is
 * on, for the result's "section › card".
 *
 * [feature] gates the row itself (core/DeviceProfile.kt): hidden with it, and where the
 * device keeps the feature for its advanced settings the row is on [aside] instead of [page].
 */
class SettingEntry internal constructor(
    val id: String,
    val page: SettingsPage,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:StringRes val keywords: Int,
    @param:StringRes val card: Int = 0,
    @param:StringRes val anchor: Int = title,
    @param:StringRes val help: Int = 0,
    val feature: Feature? = null,
    val aside: SettingsPage? = null,
    val needs: Need? = null,
    /** Offered before anything is typed. */
    val suggested: Boolean = false,
) {
    val isPage: Boolean get() = anchor == 0
}

/**
 * Where an entry is on this device, and how to get there: the page to open ([section]), the
 * titles that lead to it ([trail]), the row to bring into view and mark ([anchor], 0 for a
 * page). [reason] is non-zero when the entry's own row is not on the page now: [anchor] is
 * then the row that brings it. [fallback] is a row to mark instead, should [anchor] turn out
 * not to be there after all.
 */
class SettingPlace(
    val entry: SettingEntry,
    val section: String,
    val trail: List<Int>,
    @param:StringRes val anchor: Int,
    @param:StringRes val fallback: Int,
    @param:StringRes val reason: Int,
)

object SettingsCatalog {

    val entries: List<SettingEntry> = buildList {
        // ─── The pages themselves ────────────────────────────────────────────────────────
        fun page(page: SettingsPage, summary: Int, keywords: Int, suggested: Boolean = false) =
            add(SettingEntry(page.id, page, page.title, summary, keywords, anchor = 0, suggested = suggested))
        page(SettingsPage.APPEARANCE, R.string.settings_cat_appearance_desc, R.string.search_kw_appearance)
        page(SettingsPage.ACCOUNT, R.string.settings_cat_account_desc, R.string.search_kw_account)
        page(SettingsPage.TUNNEL, R.string.settings_cat_tunnel_desc, R.string.search_kw_tunnel, suggested = true)
        page(SettingsPage.PROXIES, R.string.settings_cat_proxies_desc, R.string.search_kw_proxies)
        page(SettingsPage.DNS, R.string.settings_cat_dns_desc, R.string.search_kw_dns)
        page(SettingsPage.BYPASS, R.string.settings_cat_bypass_desc, R.string.search_kw_bypass, suggested = true)
        page(SettingsPage.SHARING, R.string.settings_cat_sharing_desc, R.string.search_kw_sharing)
        page(SettingsPage.BACKGROUND, R.string.settings_cat_background_desc, R.string.search_kw_background)
        page(SettingsPage.BACKUP, R.string.settings_cat_backup_desc, R.string.search_kw_backup, suggested = true)
        page(SettingsPage.AUTOMATION, R.string.settings_cat_automation_desc, R.string.search_kw_automation)
        page(SettingsPage.DIAGNOSTICS, R.string.settings_cat_diagnostics_desc, R.string.search_kw_diagnostics)
        page(SettingsPage.ADVANCED, R.string.exposure_advanced_desc, R.string.search_kw_advanced)
        page(SettingsPage.THEME, R.string.tv_settings_theme_desc, R.string.search_kw_theme)
        page(SettingsPage.ROOT, R.string.tv_settings_root_desc, R.string.search_kw_mode_root)

        fun row(
            id: String, page: SettingsPage, title: Int, summary: Int, keywords: Int, card: Int = 0,
            anchor: Int = title, help: Int = 0, feature: Feature? = null, aside: SettingsPage? = null,
            needs: Need? = null, suggested: Boolean = false,
        ) = add(SettingEntry(id, page, title, summary, keywords, card, anchor, help, feature, aside, needs, suggested))

        // ─── Appearance & language ───────────────────────────────────────────────────────
        val look = R.string.settings_sect_personalization
        val theme = Feature.THEME_AND_ICON
        row("theme_mode", SettingsPage.APPEARANCE, R.string.settings_theme_title, R.string.search_sum_theme, R.string.search_kw_theme, look, feature = theme, aside = SettingsPage.THEME)
        row("language", SettingsPage.APPEARANCE, R.string.settings_lang_title, R.string.search_sum_language, R.string.search_kw_language, look, suggested = true)
        row("ui_scale", SettingsPage.APPEARANCE, R.string.settings_ui_scale_title, R.string.settings_ui_scale_desc, R.string.search_kw_ui_scale, look)
        row("interface", SettingsPage.APPEARANCE, R.string.tv_interface_title, R.string.tv_interface_desc, R.string.search_kw_interface, look)
        row("palette", SettingsPage.APPEARANCE, R.string.settings_palette_title, R.string.search_sum_palette, R.string.search_kw_palette, look, feature = theme, aside = SettingsPage.THEME, needs = Need.STATIC_COLORS)
        row("app_icon", SettingsPage.APPEARANCE, R.string.icon_title, R.string.icon_note, R.string.search_kw_app_icon, look, feature = theme, aside = SettingsPage.THEME)
        row("qr_style", SettingsPage.APPEARANCE, R.string.qr_style_title, R.string.qr_style_note, R.string.search_kw_qr_style, look)
        row("dynamic_color", SettingsPage.APPEARANCE, R.string.settings_dynamic_color_title, R.string.settings_dynamic_color_desc, R.string.search_kw_dynamic_color, look, feature = theme, aside = SettingsPage.THEME, needs = Need.ANDROID_12)
        row("amoled", SettingsPage.APPEARANCE, R.string.settings_amoled_black_title, R.string.settings_amoled_black_desc, R.string.search_kw_amoled, look, feature = theme, aside = SettingsPage.THEME)
        row("show_changelog", SettingsPage.APPEARANCE, R.string.settings_show_changelog_title, R.string.settings_show_changelog_desc, R.string.search_kw_show_changelog, look)
        row("connection_summary", SettingsPage.APPEARANCE, R.string.settings_connection_summary_title, R.string.settings_connection_summary_desc, R.string.search_kw_connection_summary, look)
        row("managed_on_main", SettingsPage.APPEARANCE, R.string.settings_managed_on_main_title, R.string.settings_managed_on_main_desc, R.string.search_kw_managed_on_main, look)
        row("update_check", SettingsPage.APPEARANCE, R.string.settings_update_check_title, R.string.settings_update_check_desc, R.string.search_kw_update_check, look, needs = Need.SELF_UPDATE)
        row("update_beta", SettingsPage.APPEARANCE, R.string.settings_update_beta_title, R.string.settings_update_beta_desc, R.string.search_kw_update_beta, look, needs = Need.SELF_UPDATE)

        // ─── Account & connection ────────────────────────────────────────────────────────
        // The first card is titled with the account's name, which a result has no room for.
        row("login_server", SettingsPage.ACCOUNT, R.string.settings_login_server_title, R.string.search_sum_login_server, R.string.search_kw_login_server)
        row("auth_key", SettingsPage.ACCOUNT, R.string.settings_auth_key_title, R.string.search_sum_auth_key, R.string.search_kw_auth_key)
        row("device_name", SettingsPage.ACCOUNT, R.string.settings_device_name_title, R.string.search_sum_device_name, R.string.search_kw_device_name)
        row("exit_node", SettingsPage.ACCOUNT, R.string.settings_exit_node_title, R.string.search_sum_exit_node, R.string.search_kw_exit_node, suggested = true)
        row("exit_node_lan", SettingsPage.ACCOUNT, R.string.client_prefs_lan_title, R.string.client_prefs_lan_desc, R.string.search_kw_exit_node_lan)
        row("accept_routes", SettingsPage.ACCOUNT, R.string.settings_accept_subnet_routes_title, R.string.settings_accept_routes_desc, R.string.search_kw_accept_routes)
        row("advertise_exit_node", SettingsPage.ACCOUNT, R.string.settings_advertise_exit_node_title, R.string.settings_advertise_exit_node_desc, R.string.search_kw_advertise_exit_node)
        row("honest_hostinfo", SettingsPage.ACCOUNT, R.string.settings_honest_hostinfo_title, R.string.settings_honest_hostinfo_desc, R.string.search_kw_honest_hostinfo)
        val security = R.string.client_prefs_sect_security
        row("shields_up", SettingsPage.ACCOUNT, R.string.client_prefs_shields_title, R.string.client_prefs_shields_desc, R.string.search_kw_shields_up, security)
        row("posture", SettingsPage.ACCOUNT, R.string.client_prefs_posture_title, R.string.client_prefs_posture_desc, R.string.search_kw_posture, security)
        row("tailnet_lock", SettingsPage.ACCOUNT, R.string.tka_title, R.string.tka_row_help, R.string.search_kw_tailnet_lock, security)
        val offers = R.string.settings_sect_service_ad
        row("advertise_routes", SettingsPage.ACCOUNT, R.string.settings_ad_routes_title, R.string.settings_ad_routes_desc, R.string.search_kw_advertise_routes, offers)
        row("advertise_tags", SettingsPage.ACCOUNT, R.string.settings_ad_tags_title, R.string.settings_ad_tags_desc, R.string.search_kw_advertise_tags, offers)
        row("relay", SettingsPage.ACCOUNT, R.string.client_prefs_relay_title, R.string.client_prefs_relay_desc, R.string.search_kw_relay, offers)
        row("relay_port", SettingsPage.ACCOUNT, R.string.client_prefs_relay_port_title, R.string.client_prefs_relay_port_desc, R.string.search_kw_relay_port, offers, needs = Need.RELAY)
        row("app_connector", SettingsPage.ACCOUNT, R.string.client_prefs_connector_title, R.string.client_prefs_connector_desc, R.string.search_kw_app_connector, offers)
        row("logout", SettingsPage.ACCOUNT, R.string.settings_logout_reset_title, R.string.search_sum_logout, R.string.search_kw_logout, R.string.settings_sect_adv_profile)

        // ─── Tunnel mode ─────────────────────────────────────────────────────────────────
        val root = Feature.ROOT_MODE
        row("mode_proxy", SettingsPage.TUNNEL, R.string.settings_tunnel_mode_proxy, R.string.settings_tunnel_mode_proxy_desc, R.string.search_kw_mode_proxy)
        row("mode_vpn", SettingsPage.TUNNEL, R.string.settings_tunnel_mode_vpn, R.string.settings_tunnel_mode_vpn_desc, R.string.search_kw_mode_vpn)
        row("mode_root", SettingsPage.TUNNEL, R.string.settings_tunnel_mode_root, R.string.settings_tunnel_mode_root_desc, R.string.search_kw_mode_root, feature = root, aside = SettingsPage.ROOT)
        val tun = R.string.settings_sect_tun_mode
        row("tun_engine", SettingsPage.TUNNEL, R.string.tun_engine_title, R.string.search_sum_tun_engine, R.string.search_kw_tun_engine, tun, help = R.string.tun_engine_native_desc, needs = Need.TUN_MODE)
        row("tun_uses_socks", SettingsPage.TUNNEL, R.string.settings_tun_uses_socks_title, R.string.search_sum_tun_uses_socks, R.string.search_kw_tun_uses_socks, tun, needs = Need.TUN_HEV)
        row("tun_swap_in_place", SettingsPage.TUNNEL, R.string.settings_tun_swap_title, R.string.settings_tun_swap_desc, R.string.search_kw_tun_swap, tun, needs = Need.TUN_NATIVE)
        row("tun_ipv6", SettingsPage.TUNNEL, R.string.settings_tun_ipv6_title, R.string.settings_tun_ipv6_desc, R.string.search_kw_tun_ipv6, tun, needs = Need.TUN_MODE)
        row("tun_transparent", SettingsPage.TUNNEL, R.string.settings_tun_transparent_title, R.string.search_sum_tun_transparent, R.string.search_kw_tun_transparent, tun, needs = Need.TUN_MODE)
        row("tun_address", SettingsPage.TUNNEL, R.string.settings_tun_address_title, R.string.settings_tun_address_desc, R.string.search_kw_tun_address, tun, needs = Need.TUN_HEV)
        row("tun_excluded_cidrs", SettingsPage.TUNNEL, R.string.settings_tun_excluded_cidrs_title, R.string.settings_tun_excluded_cidrs_desc, R.string.search_kw_tun_excluded_cidrs, needs = Need.TUN_OR_ROOT_TUN)
        row("tun_excluded_apps", SettingsPage.TUNNEL, R.string.settings_tun_excluded_apps_title, R.string.search_sum_tun_excluded_apps, R.string.search_kw_tun_excluded_apps, needs = Need.TUN_OR_ROOT)
        val rootCard = R.string.settings_root_sect_title
        row("root_tun", SettingsPage.TUNNEL, R.string.settings_root_tun_title, R.string.settings_root_tun_desc_native, R.string.search_kw_root_tun, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)
        row("root_service", SettingsPage.TUNNEL, R.string.settings_root_service_title, R.string.settings_root_service_desc, R.string.search_kw_root_service, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)
        row("root_cli", SettingsPage.TUNNEL, R.string.settings_root_cli_title, R.string.settings_root_cli_desc, R.string.search_kw_root_cli, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)
        row("root_vpn_bypass", SettingsPage.TUNNEL, R.string.settings_root_vpn_bypass_title, R.string.settings_root_vpn_bypass_desc, R.string.search_kw_root_vpn_bypass, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)
        row("root_take_device", SettingsPage.TUNNEL, R.string.settings_root_take_device_title, R.string.settings_root_take_device_desc, R.string.search_kw_root_take_device, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)
        row("root_kill_daemon", SettingsPage.TUNNEL, R.string.settings_root_kill_daemon_title, R.string.settings_root_kill_daemon_desc, R.string.search_kw_root_kill_daemon, rootCard, feature = root, aside = SettingsPage.ROOT, needs = Need.ROOT_MODE)

        // ─── Local proxies ───────────────────────────────────────────────────────────────
        val socks = R.string.settings_sect_socks5
        row("socks5_address", SettingsPage.PROXIES, R.string.settings_socks5_address_title, R.string.search_sum_socks5_address, R.string.search_kw_socks5_address, socks)
        row("socks5_username", SettingsPage.PROXIES, R.string.settings_socks5_username_title, R.string.search_sum_socks5_username, R.string.search_kw_socks5_username, socks)
        row("socks5_password", SettingsPage.PROXIES, R.string.settings_socks5_password_title, R.string.search_sum_socks5_password, R.string.search_kw_socks5_password, socks)
        row("sagernet_link", SettingsPage.PROXIES, R.string.settings_sagernet_copy, R.string.search_sum_sagernet_link, R.string.search_kw_sagernet_link, socks, feature = Feature.CLIPBOARD_ACTIONS)
        val http = R.string.settings_sect_http
        row("http_enable", SettingsPage.PROXIES, R.string.settings_http_enable_title, R.string.settings_http_enable_desc, R.string.search_kw_http_enable, http)
        row("http_address", SettingsPage.PROXIES, R.string.settings_http_address_title, R.string.settings_http_desc, R.string.search_kw_http_address, http, needs = Need.HTTP_PROXY)
        row("lan_access", SettingsPage.PROXIES, R.string.settings_lan_access_title, R.string.settings_lan_access_desc, R.string.search_kw_lan_access, R.string.settings_sect_lan)
        val tailnetReach = R.string.tailnet_reach_card
        row("tailnet_socks5", SettingsPage.PROXIES, R.string.tailnet_reach_socks5, R.string.tailnet_reach_socks5_help, R.string.search_kw_tailnet_socks5, tailnetReach)
        row("tailnet_http_proxy", SettingsPage.PROXIES, R.string.tailnet_reach_http, R.string.tailnet_reach_http_help, R.string.search_kw_tailnet_http_proxy, tailnetReach)
        row("tailnet_dns_proxy", SettingsPage.PROXIES, R.string.tailnet_reach_dns, R.string.tailnet_reach_dns_help, R.string.search_kw_tailnet_dns_proxy, tailnetReach)
        row("tailnet_drive_proxy", SettingsPage.PROXIES, R.string.tailnet_reach_drive, R.string.tailnet_reach_drive_help, R.string.search_kw_tailnet_drive_proxy, tailnetReach)
        row("tailnet_strict", SettingsPage.PROXIES, R.string.tailnet_reach_strict, R.string.tailnet_reach_strict_help, R.string.search_kw_tailnet_strict, tailnetReach)
        row("tailnet_extra_ports", SettingsPage.PROXIES, R.string.tailnet_reach_extra, R.string.tailnet_reach_extra_desc, R.string.search_kw_tailnet_extra_ports, tailnetReach, needs = Need.TAILNET_STRICT)

        // ─── DNS ─────────────────────────────────────────────────────────────────────────
        val resolver = R.string.settings_sect_resolver
        row("magicdns", SettingsPage.DNS, R.string.settings_magicdns_title, R.string.settings_accept_dns_desc, R.string.search_kw_magicdns, resolver)
        row("dns_proxy_address", SettingsPage.DNS, R.string.settings_dns_proxy_address_title, R.string.search_sum_dns_proxy_address, R.string.search_kw_dns_proxy_address, resolver)
        row("root_dns_redirect", SettingsPage.DNS, R.string.settings_root_dns_redirect_title, R.string.settings_root_dns_redirect_desc, R.string.search_kw_root_dns_redirect, resolver, needs = Need.ROOT_TUN)
        val fallbackDns = R.string.settings_sect_fallback_dns
        row("dns_fallbacks", SettingsPage.DNS, R.string.settings_dns_fallbacks_title, R.string.search_sum_dns_fallbacks, R.string.search_kw_dns_fallbacks, fallbackDns)
        row("doh_fallback", SettingsPage.DNS, R.string.settings_doh_fallback_title, R.string.search_sum_doh_fallback, R.string.search_kw_doh_fallback, fallbackDns)
        val tools = R.string.settings_sect_tools
        row("dns_screen", SettingsPage.DNS, R.string.dns_title, R.string.settings_link_dns_desc, R.string.search_kw_dns_screen, tools, feature = Feature.DNS_SCREEN, aside = SettingsPage.ADVANCED)

        // ─── Censorship bypass ───────────────────────────────────────────────────────────
        row("control_proxy", SettingsPage.BYPASS, R.string.settings_control_proxy_title, R.string.search_sum_control_proxy, R.string.search_kw_control_proxy, R.string.settings_sect_control_proxy)
        val dpi = R.string.settings_tab_byedpi
        row("byedpi", SettingsPage.BYPASS, R.string.settings_byedpi_enable, R.string.settings_byedpi_desc, R.string.search_kw_byedpi, dpi)
        row("byedpi_flags", SettingsPage.BYPASS, R.string.settings_byedpi_flags, R.string.search_sum_byedpi_flags, R.string.search_kw_byedpi_flags, dpi)
        row("byedpi_ipv6", SettingsPage.BYPASS, R.string.settings_byedpi_ipv6_disabled, R.string.settings_byedpi_ipv6_disabled_desc, R.string.search_kw_byedpi_ipv6, dpi)
        row("control_port", SettingsPage.BYPASS, R.string.core_control_port_title, R.string.core_control_port_desc, R.string.search_kw_control_port, R.string.core_control_port_section, help = R.string.core_control_port_help)
        val netmap = R.string.core_netmap_cache_section
        row("netmap_cache", SettingsPage.BYPASS, R.string.core_netmap_cache_title, R.string.core_netmap_cache_desc, R.string.search_kw_netmap_cache, netmap, help = R.string.core_netmap_cache_help)
        row("netmap_cache_clear", SettingsPage.BYPASS, R.string.core_netmap_cache_clear_title, R.string.core_netmap_cache_clear_desc, R.string.search_kw_netmap_cache_clear, netmap)

        // ─── Sharing & access ────────────────────────────────────────────────────────────
        row("taildrop_folder", SettingsPage.SHARING, R.string.settings_taildrop_folder_title, R.string.search_sum_taildrop_folder, R.string.search_kw_taildrop_folder, R.string.settings_sect_storage)
        row("taildrive_download_folder", SettingsPage.SHARING, R.string.taildrive_download_folder_title, R.string.search_sum_taildrive_download_folder, R.string.search_kw_taildrive_download_folder, R.string.settings_sect_storage)
        val shares = R.string.settings_sect_share_links
        row("taildrive", SettingsPage.SHARING, R.string.taildrive_title, R.string.settings_link_taildrive_desc, R.string.search_kw_taildrive, shares)
        row("serve", SettingsPage.SHARING, R.string.serve_title, R.string.settings_link_serve_desc, R.string.search_kw_serve, shares, feature = Feature.SERVE_EDIT, aside = SettingsPage.ADVANCED)
        val web = R.string.settings_sect_web
        row("web_ui", SettingsPage.SHARING, R.string.settings_web_enable_title, R.string.settings_web_enable_desc, R.string.search_kw_web_ui, web)
        row("web_ui_address", SettingsPage.SHARING, R.string.settings_web_address_title, R.string.search_sum_web_ui_address, R.string.search_kw_web_ui_address, web, needs = Need.WEB_UI)

        // ─── Background & permissions ────────────────────────────────────────────────────
        val service = R.string.settings_sect_service_perms
        row("permissions", SettingsPage.BACKGROUND, R.string.settings_permissions_title, R.string.settings_permissions_desc, R.string.search_kw_permissions, service, suggested = true)
        row("autostart", SettingsPage.BACKGROUND, R.string.settings_autostart_title, R.string.settings_autostart_desc, R.string.search_kw_autostart, service)
        row("auto_reconnect", SettingsPage.BACKGROUND, R.string.settings_auto_reconnect_title, R.string.settings_auto_reconnect_desc, R.string.search_kw_auto_reconnect, service)
        row("auto_reconnect_attempts", SettingsPage.BACKGROUND, R.string.settings_auto_reconnect_attempts_title, R.string.search_sum_auto_reconnect_attempts, R.string.search_kw_auto_reconnect_attempts, service, needs = Need.AUTO_RECONNECT)
        row("watchdog", SettingsPage.BACKGROUND, R.string.settings_watchdog_title, R.string.settings_watchdog_desc, R.string.search_kw_watchdog, service)
        row("keep_awake", SettingsPage.BACKGROUND, R.string.settings_keep_awake_title, R.string.settings_force_bg_desc, R.string.search_kw_keep_awake, service)

        // ─── Backup & restore ────────────────────────────────────────────────────────────
        val files = R.string.settings_sect_backup_files
        row("backup_full", SettingsPage.BACKUP, R.string.settings_backup_full_title, R.string.search_sum_backup_full, R.string.search_kw_backup_full, files)
        row("restore_full", SettingsPage.BACKUP, R.string.settings_restore_full_title, R.string.search_sum_restore_full, R.string.search_kw_restore_full, files)
        row("profile_export", SettingsPage.BACKUP, R.string.settings_profile_export_title, R.string.search_sum_profile_export, R.string.search_kw_profile_export, files)
        row("profile_import", SettingsPage.BACKUP, R.string.settings_profile_import_title, R.string.search_sum_profile_import, R.string.search_kw_profile_import, files)

        // ─── Automation & API ────────────────────────────────────────────────────────────
        val tasker = R.string.settings_automation_title
        row("automation_enable", SettingsPage.AUTOMATION, R.string.settings_automation_enable_title, R.string.settings_automation_enable_desc, R.string.search_kw_automation_enable, tasker)
        row("automation_token", SettingsPage.AUTOMATION, R.string.settings_automation_token_label, R.string.search_sum_automation_token, R.string.search_kw_automation_token, tasker, needs = Need.AUTOMATION)
        row("admin_console", SettingsPage.AUTOMATION, R.string.admin_console_title, R.string.settings_link_admin_desc, R.string.search_kw_admin_console, R.string.settings_sect_admin_api, feature = Feature.ADMIN_CONSOLE)

        // ─── Diagnostics & developer ─────────────────────────────────────────────────────
        row("live_state", SettingsPage.DIAGNOSTICS, R.string.diag_live_title, R.string.search_sum_live_state, R.string.search_kw_live_state, help = R.string.diag_live_help)
        val flags = R.string.settings_sect_flags_logs
        row("detailed_logs", SettingsPage.DIAGNOSTICS, R.string.settings_detailed_logs_title, R.string.settings_detailed_logs_desc, R.string.search_kw_detailed_logs, flags)
        row("extra_args", SettingsPage.DIAGNOSTICS, R.string.settings_extra_args_title, R.string.extra_args_desc, R.string.search_kw_extra_args, flags)
        // The card of a daemon run by root: found by its title, opened on its first line.
        row("root_daemon_info", SettingsPage.DIAGNOSTICS, R.string.settings_root_info_title, R.string.search_sum_root_daemon_info, R.string.search_kw_root_daemon_info, anchor = R.string.settings_diag_socket_path, needs = Need.ROOT_MODE)
        row("logs", SettingsPage.DIAGNOSTICS, R.string.logs_title, R.string.settings_link_logs_desc, R.string.search_kw_logs, tools, feature = Feature.LOGS, aside = SettingsPage.ADVANCED)
        row("console", SettingsPage.DIAGNOSTICS, R.string.console_title, R.string.settings_link_console_desc, R.string.search_kw_console, tools, feature = Feature.CONSOLE, aside = SettingsPage.ADVANCED)
        row("netcheck", SettingsPage.DIAGNOSTICS, R.string.netcheck_title, R.string.settings_link_netcheck_desc, R.string.search_kw_netcheck, tools)
        row("show_onboarding", SettingsPage.DIAGNOSTICS, R.string.settings_show_onboarding, R.string.settings_show_onboarding_desc, R.string.search_kw_show_onboarding, R.string.settings_sect_troubleshooting)

        // ─── Advanced ────────────────────────────────────────────────────────────────────
        row("exposure", SettingsPage.ADVANCED, R.string.exposure_title, R.string.exposure_entry_desc, R.string.search_kw_exposure, R.string.exposure_card)
        row("remote_management", SettingsPage.ADVANCED, R.string.remote_title, R.string.settings_remote_entry_desc, R.string.search_kw_remote_management, R.string.remote_experimental)
    }

    private val byId: Map<String, SettingEntry> = entries.associateBy { it.id }

    fun byId(id: String?): SettingEntry? = byId[id]

    /**
     * Where [entry] is on a device of class [device] that can do [caps], with [facts] as they
     * stand; null when the device is not offered it. The rules are SettingsActivity's own:
     * what a device hides is nowhere, a page whose feature it keeps for the advanced settings
     * is under Advanced, and a row whose feature it keeps there is on the row's [SettingEntry.aside]
     * page.
     */
    fun place(entry: SettingEntry, device: DeviceClass, caps: Capabilities, facts: SettingsFacts): SettingPlace? {
        fun where(feature: Feature?): Availability =
            if (feature == null) Availability.OFFERED else DeviceProfile.availability(feature, device, caps)

        val own = where(entry.feature)
        if (own == Availability.HIDDEN) return null
        val page = if (own == Availability.ADVANCED && entry.aside != null) entry.aside else entry.page
        val pageIs = where(page.feature)
        if (pageIs == Availability.HIDDEN) return null
        // A page that exists only where its feature left its usual place: elsewhere there is no such page.
        if (page.aside && pageIs != Availability.ADVANCED) return null
        val underAdvanced = page != SettingsPage.ADVANCED && pageIs == Availability.ADVANCED
        val trail = buildList {
            if (entry.isPage) {
                add(if (underAdvanced) SettingsPage.ADVANCED.title else R.string.settings_title)
            } else {
                if (underAdvanced) add(SettingsPage.ADVANCED.title)
                add(page.title)
                if (entry.card != 0) add(entry.card)
            }
        }

        val need = entry.needs
        val bringer = need?.broughtBy?.let(::byId)
        if (need != null && !facts.met(need)) {
            // Nothing brings it on this device: not offered. Otherwise the result opens what does.
            val there = bringer?.let { place(it, device, caps, facts) } ?: return null
            return SettingPlace(entry, there.section, trail, there.anchor, there.fallback, need.reason)
        }
        // The facts are read from what is stored; the page decides for itself. Should the row not be
        // there after all, the row that brings it is marked — when it is on the same page.
        val fallback = bringer?.let { place(it, device, caps, facts) }?.takeIf { it.section == page.id && it.reason == 0 }?.anchor ?: 0
        return SettingPlace(entry, page.id, trail, entry.anchor, fallback, 0)
    }
}
