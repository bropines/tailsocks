package io.github.bropines.tailscaled.core

/*
 * Decisions the service makes around tearing a daemon down, as plain functions
 * (TailscaledService and TunVpnService act on them).
 *
 * They came out of one bug: switching a running connection from VPN to Root Mode
 * left the phone disconnected with "The daemon stopped on its own". The restart
 * stopped the VpnService, which relaunched a userspace daemon on its way out; the
 * restart's teardown, reading the already-flipped Root Mode setting, only
 * *detached* the bridge from it and had the root kill script take it down from
 * outside, so the Go supervisor saw its current daemon die and reported a crash;
 * the crash stand-down then superseded the restart's own start.
 */

/** What an exit of the bridge's own daemon process means. */
internal enum class DaemonExit {
    /** A stop, restart or recovery of the service's own is taking it down: expected. */
    EXPECTED,

    /** It died on its own while the connection was wanted: the crash path (onDaemonExited). */
    CRASH,
}

/**
 * Classifies an exit the Go supervisor reported.
 *
 * [teardownStarted]: a stop (or the crash stand-down) owns the service. [userWantsRunning]:
 * desired_running. [ownTeardownInFlight]: a restart or auto-reconnect is between tearing the
 * daemon down and starting it again — those clear teardownStarted *before* their teardown, so
 * that a Stop landing meanwhile is not swallowed, and the flag alone therefore cannot tell
 * their own kill from a crash.
 */
internal fun classifyDaemonExit(
    teardownStarted: Boolean,
    userWantsRunning: Boolean,
    ownTeardownInFlight: Boolean,
): DaemonExit =
    if (teardownStarted || !userWantsRunning || ownTeardownInFlight) DaemonExit.EXPECTED else DaemonExit.CRASH

/**
 * Whether taking the native TUN device down relaunches the daemon in userspace mode.
 *
 * Only when the daemon is meant to outlive the tunnel ([daemonStays]: a settings apply
 * switching TUN off, another VPN app taking the slot) and the connection is wanted. A
 * restart, a recovery, a stop or a crash stand-down tears the daemon down right after
 * the tunnel; relaunching it there started a daemon the teardown was about to kill — and,
 * since a launch takes a few hundred milliseconds, sometimes one that came up after the
 * kill and survived it.
 */
internal fun relaunchAfterTunStop(daemonStays: Boolean, userWantsRunning: Boolean): Boolean =
    daemonStays && userWantsRunning

/**
 * Whether the refresh tick may install or check the Root Mode ruleset now.
 *
 * The settings say Root Mode the moment the user picks it, while the daemon that is
 * running is still the userspace one the switch is about to replace: applying the
 * tailscale0 ruleset then ran against a daemon that has no tailscale0 ("Cannot find
 * device"). Only a root daemon this service started or adopted ([rootDaemonAttached])
 * gets the rules.
 */
internal fun rootRoutingApplies(rootMode: Boolean, rootTun: Boolean, rootDaemonAttached: Boolean): Boolean =
    rootMode && rootTun && rootDaemonAttached
