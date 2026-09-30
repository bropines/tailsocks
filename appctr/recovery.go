package appctr

import (
	"log/slog"
	"strings"
	"sync"
	"syscall"
	"time"
)

// The shortest gap between two relay kicks. A link that flaps — which is what
// a congested cell does — would otherwise have us tearing down relay
// connections faster than they can be established, which is the opposite of
// recovering.
const relayKickInterval = 20 * time.Second

var (
	recoveryMu   sync.Mutex
	lastKickedAt time.Time
)

// NetworkBecameUsable is called when Android says the network it is on can
// reach the internet again — the platform's own verdict, not ours.
//
// This is the case no interface change can describe: the phone keeps its
// address and its interface, the cell simply stops passing traffic and starts
// again some minutes later. The daemon has nothing to react to, so it waits
// out its own backoff — a relay reconnect is attempted every ten to fifteen
// seconds, each attempt allowed ten to fail — and the home relay cannot even
// be re-chosen while the control plane is unreachable. The result is that the
// tunnel comes back well after the network did.
//
// So when the platform says the path is good again we wake the daemon's
// monitor, and if it is still telling us the relays are unreachable, we break
// those dead connections and ask for a fresh netcheck. This cannot rescue a
// link that is down — nothing on the phone can — it only removes the waiting
// once the link is up.
//
// It costs nothing to leave on: it runs on a system callback that fires when
// the platform re-validates a network, does nothing unless the daemon is
// complaining, and does nothing twice within [relayKickInterval]. There is no
// timer and no polling anywhere in this path.
func NetworkBecameUsable() {
	stateMu.Lock()
	proc := cmd
	stateMu.Unlock()
	if proc == nil || proc.Process == nil {
		return
	}

	// Free, and worth doing whether or not the rest applies: the monitor
	// re-reads the interfaces and notices anything that moved with the link.
	_ = proc.Process.Signal(syscall.SIGUSR1)

	if !relaysAreStuck() {
		return
	}

	recoveryMu.Lock()
	if time.Since(lastKickedAt) < relayKickInterval {
		recoveryMu.Unlock()
		return
	}
	lastKickedAt = time.Now()
	recoveryMu.Unlock()

	slog.Info("Network is usable again and the relays are not, reconnecting them")
	if _, err := doLocalRequest("POST", "/localapi/v0/debug?action=break-derp-conns", nil); err != nil {
		slog.Debug("Could not drop the dead relay connections", "err", err)
		return
	}
	if _, err := doLocalRequest("POST", "/localapi/v0/debug?action=restun", nil); err != nil {
		slog.Debug("Could not ask for a fresh netcheck", "err", err)
	}
}

// relaysAreStuck reports whether the daemon is currently complaining about the
// relays. The codes come from tsconst/health.go; they are raised only after
// the condition has held for a few seconds, so a passing blip does not qualify.
func relaysAreStuck() bool {
	for _, w := range GetBusState().Health {
		if strings.Contains(w.Code, "derp") {
			return true
		}
	}
	return false
}

// manualKickInterval spaces out kicks the user asks for with the button: short
// enough that a second tap after a failed attempt works, long enough that
// hammering it does not tear relay connections down faster than they form.
const manualKickInterval = 5 * time.Second

var lastManualKickAt time.Time

// ReconnectRelays drops the daemon's relay connections and asks for a fresh
// netcheck, unconditionally — the button behind a "relays unreachable"
// warning. Where NetworkBecameUsable waits for the platform's word and for the
// daemon to be complaining, this is the user saying "try again now". Returns
// "" on success, or the reason it could not.
func ReconnectRelays() string {
	if !IsRunning() {
		return errNotRunning.Error()
	}
	recoveryMu.Lock()
	if time.Since(lastManualKickAt) < manualKickInterval {
		recoveryMu.Unlock()
		return ""
	}
	lastManualKickAt = time.Now()
	lastKickedAt = lastManualKickAt
	recoveryMu.Unlock()

	slog.Info("Reconnecting the relays at the user's request")
	if _, err := doLocalRequest("POST", "/localapi/v0/debug?action=break-derp-conns", nil); err != nil {
		return err.Error()
	}
	if _, err := doLocalRequest("POST", "/localapi/v0/debug?action=restun", nil); err != nil {
		return err.Error()
	}
	return ""
}

// LastRelayKickMs is when the relays were last reconnected, automatically or
// by hand, in Unix milliseconds; 0 if never. For the diagnostics card.
func LastRelayKickMs() int64 {
	recoveryMu.Lock()
	defer recoveryMu.Unlock()
	if lastKickedAt.IsZero() {
		return 0
	}
	return lastKickedAt.UnixMilli()
}

// dnsRecheckInterval spaces out the queries that re-check a DNS warning.
const dnsRecheckInterval = time.Minute

var lastDNSRecheckAt time.Time

// recheckDNSWarning asks the daemon's resolver one question while it is
// reporting "DNS unavailable", at most once a minute.
//
// The daemon raises that warning when a query it forwarded timed out and
// clears it on the next one that succeeds — it never looks again by itself.
// In proxy mode few queries reach its resolver at all, so one bad spell (an
// exit node's DoH out of reach from some network) left the warning, and the
// "connection problem" on the card, standing for hours after the resolver was
// fine again. The answer does not matter: a success clears the warning, a
// failure renews it, and either way it says how things are now.
//
// Called from GetHealthWarningsJSON, which the card and the notification read
// every few seconds; nothing runs while the warning is absent.
func recheckDNSWarning(warnings []BusHealthWarning) {
	stale := false
	for _, w := range warnings {
		if w.Code == "dns-forward-failing" {
			stale = true
			break
		}
	}
	if !stale {
		return
	}
	recoveryMu.Lock()
	if time.Since(lastDNSRecheckAt) < dnsRecheckInterval {
		recoveryMu.Unlock()
		return
	}
	lastDNSRecheckAt = time.Now()
	recoveryMu.Unlock()

	go func() {
		if _, err := doLocalRequest("GET", "/localapi/v0/dns-query?name=controlplane.tailscale.com&type=A", nil); err != nil {
			slog.Debug("DNS re-check: the resolver still does not answer", "err", err)
		}
	}()
}
