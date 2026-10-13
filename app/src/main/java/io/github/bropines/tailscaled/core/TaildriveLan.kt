package io.github.bropines.tailscaled.core

import java.security.SecureRandom
import java.util.Random

/*
 * "Allow from the local network": the one switch that decides who can reach the Taildrive proxy,
 * apart from the drawing. The switch has no preference of its own. It is a reading of the proxy's
 * bind address (taildrive_proxy_ip), which TailscaledService.applyTaildrive starts the proxy from
 * and DriveProxy connects to, so neither has to know the switch exists:
 *
 *  - loopback: off, this device alone;
 *  - every interface: on. That is 0.0.0.0, the IPv6 wildcard, and also a value stored empty: the
 *    service hands the proxy ":port", which listens on all of them. The screen no longer stores
 *    an empty value (an emptied field means the default, loopback), but one stored by an older
 *    build is read as what it does;
 *  - anything else was typed by hand into the advanced field: custom. The switch names the
 *    address and claims neither; choosing on or off there is explicit.
 *
 * On comes with a login. The proxy is a door to every share of the tailnet, and on a wildcard
 * address anyone on the same Wi-Fi can walk up to it. So letting the network in turns "Require
 * authentication" on, makes a login where there is none, and keeps it on until the network is let
 * out again; what the switch was before is remembered (KEY_AUTH_BEFORE_LAN) and comes back then.
 *
 * MIGRATION. An install that had a wildcard typed in before the switch existed comes up as on.
 * If it ran without a login, it still does: the screen says so in a notice that cannot be
 * dismissed and offers the one action that requires a login ([DriveLanSettings.openToNetwork],
 * [DriveLan.requireLogin]). Turning the login on for the user was the other choice, and it was
 * turned down. Nothing can do that but code running unasked: in the service it would answer a
 * player in another room with a login prompt while nobody is looking at this app, and on the
 * screen it would close no sooner than the notice lets the user close it, while breaking that
 * player as a side effect of opening a page. The open door is the user's own earlier setting, not
 * one this switch made; it is named plainly and left one press from closed. From the first thing
 * the user does about it (the notice's action, the login switch, the network switch, a new
 * address) the rule is everyone's: on means a login, and the login cannot be switched off.
 */

/** Who the proxy listens to, as the switch shows it. */
enum class DriveLanState {
    /** Loopback: this device alone. */
    OFF,

    /** Every interface: the local network is let in. */
    ON,

    /** An address set by hand that is neither. */
    CUSTOM,
}

/**
 * The stored settings the switch reads and writes: the bind address, the login switch and the
 * login, as the proxy's preferences have them, and [authBeforeLan] — what the login switch was
 * when the network was let in, null when nothing is remembered. [toString] names no password.
 */
data class DriveLanSettings(
    val bind: String,
    val auth: Boolean,
    val user: String,
    val password: String,
    val authBeforeLan: Boolean? = null,
) {
    val state: DriveLanState get() = DriveLan.stateOf(bind)

    /** The proxy checks a login: the switch is on and both fields are filled, which is how the daemon counts it. */
    val hasLogin: Boolean get() = auth && user.isNotEmpty() && password.isNotEmpty()

    /** The local network is let in and nothing is asked of it: only a setting older than the switch can be this. */
    val openToNetwork: Boolean get() = state == DriveLanState.ON && !hasLogin

    /** The login switch cannot be turned off, nor the login emptied: the local network is let in. */
    val loginRequired: Boolean get() = state == DriveLanState.ON && auth

    override fun toString(): String =
        "DriveLanSettings(bind=$bind, auth=$auth, user=$user, password=***, authBeforeLan=$authBeforeLan)"
}

object DriveLan {
    /** What "Require authentication" was when the local network was let in; absent when nothing is remembered. */
    const val KEY_AUTH_BEFORE_LAN = "taildrive_proxy_auth_before_lan"

    const val LOOPBACK = "127.0.0.1"
    const val EVERY_INTERFACE = "0.0.0.0"
    const val DEFAULT_USER = "tailsocks"

    /** [bind] as an address to name: trimmed, an IPv6 literal out of its brackets. */
    fun host(bind: String): String = bind.trim().removePrefix("[").removeSuffix("]")

    fun stateOf(bind: String): DriveLanState {
        val host = host(bind)
        return when {
            DriveAddresses.isWildcard(host) -> DriveLanState.ON
            DriveAddresses.isLoopback(host) -> DriveLanState.OFF
            else -> DriveLanState.CUSTOM
        }
    }

    /**
     * What the bind field shows for the stored value: the value, and for one an older build
     * stored empty the address that it is. An empty field would show its hint, the default,
     * which is the one thing such a proxy does not listen on.
     */
    fun shown(bind: String): String = bind.ifBlank { EVERY_INTERFACE }

    /**
     * What the bind field stores for what was typed into it. Nothing means the default, loopback:
     * stored empty it would listen on every interface, which nobody clearing a field asks for. An
     * IPv6 literal goes into brackets, for the service joins the address and the port with a colon.
     */
    fun stored(typed: String): String {
        val text = typed.trim()
        return when {
            text.isEmpty() -> LOOPBACK
            !text.startsWith("[") && text.count { it == ':' } >= 2 -> "[$text]"
            else -> text
        }
    }

    /** The switch turned on. */
    fun allow(settings: DriveLanSettings, newPassword: () -> String = { generatePassword() }): DriveLanSettings =
        bindTo(settings, EVERY_INTERFACE, newPassword)

    /** The switch turned off. */
    fun deviceOnly(settings: DriveLanSettings): DriveLanSettings = bindTo(settings, LOOPBACK)

    /**
     * The proxy bound to [typed], by the switch or by the advanced field, with what that does to
     * the login. Every interface: a login is required, and what the switch was is remembered.
     * Loopback: the login switch goes back to what was remembered. A custom address changes
     * neither — it may still be reached from outside, so a login that is on stays on.
     */
    fun bindTo(settings: DriveLanSettings, typed: String, newPassword: () -> String = { generatePassword() }): DriveLanSettings {
        val bind = stored(typed)
        return when (stateOf(bind)) {
            DriveLanState.ON -> withLogin(
                settings.copy(
                    bind = bind,
                    // On loopback the switch is the user's choice for local use, whatever was remembered.
                    authBeforeLan = if (settings.state == DriveLanState.OFF) settings.auth else settings.authBeforeLan ?: settings.auth
                ),
                newPassword
            )
            DriveLanState.OFF -> settings.copy(bind = bind, auth = settings.authBeforeLan ?: settings.auth, authBeforeLan = null)
            DriveLanState.CUSTOM -> settings.copy(bind = bind)
        }
    }

    /**
     * The login switch pressed. While the local network is let in it only goes on. Elsewhere it
     * is the user's own, and a choice made by hand replaces the remembered one.
     */
    fun setAuth(settings: DriveLanSettings, wanted: Boolean, newPassword: () -> String = { generatePassword() }): DriveLanSettings = when {
        settings.state == DriveLanState.ON -> if (wanted) requireLogin(settings, newPassword) else settings
        wanted -> withLogin(settings.copy(authBeforeLan = null), newPassword)
        else -> settings.copy(auth = false, authBeforeLan = null)
    }

    /**
     * The action of the migration notice: a login for a proxy that let the network in without
     * one. What the switch was is remembered as it is when the network is let in by the switch.
     */
    fun requireLogin(settings: DriveLanSettings, newPassword: () -> String = { generatePassword() }): DriveLanSettings =
        withLogin(settings.copy(authBeforeLan = settings.authBeforeLan ?: settings.auth), newPassword)

    /** A new user name; while a login is required, emptying it — which would switch the login off — is refused. */
    fun setUser(settings: DriveLanSettings, typed: String): DriveLanSettings =
        if (settings.loginRequired && typed.isEmpty()) settings else settings.copy(user = typed)

    /** A new password, under the same rule as [setUser]. */
    fun setPassword(settings: DriveLanSettings, typed: String): DriveLanSettings =
        if (settings.loginRequired && typed.isEmpty()) settings else settings.copy(password = typed)

    /** The login switched on, with a login the proxy will check: the stored one, or the default name and a new password. */
    private fun withLogin(settings: DriveLanSettings, newPassword: () -> String): DriveLanSettings = settings.copy(
        auth = true,
        user = settings.user.ifEmpty { DEFAULT_USER },
        password = settings.password.ifEmpty(newPassword)
    )

    /**
     * What the address card shows for [settings] and [port]. [lans] are this device's addresses on
     * the local network, the likeliest first ([DriveAddresses.lanAddresses]): the first is the
     * address named and put into the code, the rest are named after it.
     */
    fun address(settings: DriveLanSettings, port: String, lans: List<String>): DriveAddress =
        DriveAddresses.of(settings.bind, port, settings.auth, settings.user, settings.password, lans.firstOrNull(), lans.drop(1))

    /** Letters and digits that are not taken for one another when a password is read off one screen and typed on another. */
    private const val PASSWORD_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PASSWORD_LENGTH = 10

    /** A password for a login nobody chose. */
    fun generatePassword(random: Random = SecureRandom()): String =
        (1..PASSWORD_LENGTH).map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")
}
