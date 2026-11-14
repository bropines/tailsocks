package appctr

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"time"
)

// syncSettings pushes opt to the daemon once its LocalAPI answers. ctx is the
// run the options belong to: when it is cancelled (Stop, Detach, restart) the
// sync stops before it can PATCH the successor daemon with these options.
func syncSettings(ctx context.Context, opt *StartOptions) {
	if opt == nil {
		return
	}
	go func() {
		if !waitForLocalAPI(ctx, 15*time.Second) {
			if ctx.Err() == nil {
				slog.Warn("syncSettings: LocalAPI never became ready, settings not applied")
			}
			return
		}

		// Check backend status; skip PATCH /prefs if in NeedsLogin state to avoid resetting controlclient / interrupting interactive login
		statusDataStr, err := GetStatusJSON(false)
		if err == nil && len(statusDataStr) > 0 {
			var st struct {
				BackendState string `json:"BackendState"`
				AuthURL      string `json:"AuthURL"`
			}
			if json.Unmarshal([]byte(statusDataStr), &st) == nil {
				if st.BackendState == "NeedsLogin" || st.AuthURL != "" {
					slog.Info("syncSettings: backend is in NeedsLogin state, skipping PATCH /prefs to preserve login flow")
					return
				}
			}
		}

		prefs := make(map[string]interface{})
		if opt.Hostname != "" {
			prefs["Hostname"] = opt.Hostname
			prefs["HostnameSet"] = true
		}
		prefs["RouteAll"] = opt.AcceptRoutes
		prefs["RouteAllSet"] = true
		prefs["CorpDNS"] = opt.AcceptDNS
		prefs["CorpDNSSet"] = true
		controlURL := opt.LoginServer
		if controlURL == "" {
			controlURL = defaultControlURL
		}
		prefs["ControlURL"] = controlURL
		prefs["ControlURLSet"] = true

		// Push upstream resolvers to daemon so it can forward external DNS
		// queries even when no ExitNode is active (avoids "no upstream resolvers" SERVFAIL).
		var resolvers []map[string]interface{}
		if opt.DohFallback != "" && opt.DohFallback != "none" {
			resolvers = append(resolvers, map[string]interface{}{"Addr": opt.DohFallback})
		} else {
			resolvers = append(resolvers, map[string]interface{}{"Addr": "https://1.1.1.1/dns-query"})
		}

		if opt.DnsFallbacks != "" {
			for _, addr := range strings.Split(opt.DnsFallbacks, ",") {
				addr = strings.TrimSpace(addr)
				if addr != "" {
					if !strings.Contains(addr, ":") {
						addr = addr + ":53"
					}
					resolvers = append(resolvers, map[string]interface{}{"Addr": addr})
				}
			}
		} else {
			resolvers = append(resolvers, map[string]interface{}{"Addr": "8.8.8.8:53"})
			resolvers = append(resolvers, map[string]interface{}{"Addr": "1.1.1.1:53"})
		}

		if len(resolvers) > 0 {
			prefs["OverrideDNSResolvers"] = resolvers
			prefs["OverrideDNSResolversSet"] = true
		}

		// Under the lock: SetPrefs and the native TUN relaunch write this
		// field of the same options while a sync is in flight.
		stateMu.Lock()
		exitNodeID := opt.ExitNodeID
		stateMu.Unlock()
		prefs["ExitNodeID"] = exitNodeID
		prefs["ExitNodeIDSet"] = true
		prefs["ExitNodeIP"] = ""
		prefs["ExitNodeIPSet"] = true
		prefs["RunWebClient"] = opt.EnableWebUI
		prefs["RunWebClientSet"] = true
		prefs["WantRunning"] = true
		prefs["WantRunningSet"] = true
		clearStaleSSH(prefs)

		// Settings' own node switches, then the user's "Extra Arguments",
		// parsed as `tailscale up` flags and folded in last, on top of
		// everything else, so the escape hatch can override the app's own
		// settings — that is the whole point of it. Keep the smaller payloads
		// around: the daemon validates the whole MaskedPrefs batch at once
		// (checkPrefsLocked joins the errors), so a flag or a switch it refuses
		// would otherwise take every setting down with it.
		coreJSON, _ := json.Marshal(prefs)
		client := mergeClientPrefs(prefs, opt.ClientPrefs)
		baseJSON, _ := json.Marshal(prefs)
		extra := applyExtraArgs(prefs, opt.ExtraUpArgs)

		// The status round-trip above may have outlived the run; do not hand
		// these prefs to whichever daemon owns the socket now.
		if ctx.Err() != nil {
			return
		}
		jsonData, _ := json.Marshal(prefs)
		slog.Info("Syncing settings via LocalAPI", "payload", string(jsonData))
		err = PatchPrefsJSON(string(jsonData))
		if err != nil && len(extra) > 0 {
			if ctx.Err() != nil {
				return
			}
			slog.Error("The daemon rejected the settings; retrying without the extra arguments", "error", err)
			err = PatchPrefsJSON(string(baseJSON))
		}
		if err != nil && len(client) > 0 {
			if ctx.Err() != nil {
				return
			}
			slog.Error("The daemon rejected the settings; retrying without the node switches", "error", err)
			err = PatchPrefsJSON(string(coreJSON))
		}
		if err != nil {
			slog.Error("Failed to sync settings via LocalAPI", "error", err)
		}

		if ctx.Err() != nil {
			return
		}
		if opt.EnableWebUI {
			StartWebUI(opt.WebUIAddr)
		} else {
			StopWebUI()
		}
	}()
}

// mergeClientPrefs copies the node switches in raw (StartOptions.ClientPrefs)
// into prefs and returns them, or nil when there are none or raw is not a JSON
// object — a broken value costs the switches, never the rest of the sync.
// clearStaleSSH turns the SSH server off in the prefs of a daemon that is not
// Root Mode's. Such a daemon refuses RunSSH, and it checks the prefs an edit
// would leave, not the edit: with RunSSH kept on in the state from a Root Mode
// run it refuses every later change — exit node, routes, tags, the hostname.
// Root Mode sends the profile's switch again at its next start.
func clearStaleSSH(prefs map[string]any) {
	stateMu.Lock()
	external := externalSocketPath != ""
	stateMu.Unlock()
	if external {
		return
	}
	prefs["RunSSH"] = false
	prefs["RunSSHSet"] = true
}

func mergeClientPrefs(prefs map[string]any, raw string) map[string]any {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	var client map[string]any
	if err := json.Unmarshal([]byte(raw), &client); err != nil {
		slog.Warn("syncSettings: node switches ignored", "error", err)
		return nil
	}
	for k, v := range client {
		prefs[k] = v
	}
	return client
}

func ApplySettings(opt *StartOptions) {
	stateMu.Lock()
	old := lastOptions
	stateMu.Unlock()

	// First, and whatever happens below: switching remote management off must
	// not wait for a login to finish or a restart to be decided.
	remoteSwitch(opt)

	if !IsRunning() {
		slog.Info("Tailscaled not running, performing full start")
		Start(opt)
		return
	}

	if GetLoginURL() != "" {
		// A login in progress is kept from every change but the one that makes
		// it pointless: its link is for a server the user has just replaced.
		if old != nil && old.LoginServer != opt.LoginServer {
			slog.Info("Login server changed while a login was in progress, performing full restart")
			Start(opt)
			return
		}
		slog.Info("Login in progress, ignoring ApplySettings to protect session")
		return
	}

	if old == nil {
		stateMu.Lock()
		lastOptions = opt
		dnsRescueOff.Store(opt != nil && opt.DnsRescueOff)
		stateMu.Unlock()
		ReUp()
		syncSettings(currentDaemonCtx(), opt)
		return
	}

	if old.Socks5Server != opt.Socks5Server ||
		old.HttpProxy != opt.HttpProxy ||
		old.ControlProxy != opt.ControlProxy ||
		old.Socks5User != opt.Socks5User ||
		old.Socks5Pass != opt.Socks5Pass ||
		old.StatePath != opt.StatePath ||
		old.LoginServer != opt.LoginServer ||
		old.NetmapCacheOff != opt.NetmapCacheOff ||
		old.DnsRescueOff != opt.DnsRescueOff ||
		old.ControlPort80 != opt.ControlPort80 {
		stateMu.Lock()
		external := externalSocketPath != ""
		stateMu.Unlock()
		if !external {
			slog.Info("Critical settings or account changed, performing full restart")
			Start(opt)
			return
		}
		// Attached to a daemon this bridge did not launch (Root Mode): Start would
		// launch a second one beside it, which dies on the listeners the first one
		// holds and takes the session down. What the daemon reads at start is its
		// owner's to change, by restarting it (the service does, on a changed
		// RootDaemonSpec); everything that can be applied live is applied below.
		slog.Warn("Critical settings changed while attached to an external daemon; applying what can be applied live")
	}

	stateMu.Lock()
	lastOptions = opt
	dnsRescueOff.Store(opt != nil && opt.DnsRescueOff)
	stateMu.Unlock()
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

	if old.DnsProxy != opt.DnsProxy ||
		old.DnsFallbacks != opt.DnsFallbacks ||
		old.DohFallback != opt.DohFallback {
		slog.Info("DNS settings changed, restarting DNS proxy only")
		RestartDNS()
	}

	if opt.AuthKey != "" && opt.AuthKey != old.AuthKey {
		slog.Info("AuthKey changed, triggering Login via LocalAPI")
		Login(opt.AuthKey)
		return
	}

	if opt.DoReset {
		slog.Info("Reset requested via Logout")
		Logout()
		consumeReset()
		return
	}

	syncSettings(currentDaemonCtx(), opt)
}

// consumeReset clears the one-shot reset flag once the reset has been carried
// out. The app clears the preference behind it as soon as it builds the
// options, but the options themselves live for as long as the process: until
// this ran, every later resume found DoReset again and logged the profile out
// — right after the browser login had finished — and wiped its state directory.
func consumeReset() {
	stateMu.Lock()
	if lastOptions != nil {
		lastOptions.DoReset = false
	}
	stateMu.Unlock()
}

// backendState returns the daemon's own verdict — "Running", "NeedsLogin" and
// so on — or "" when the status cannot be read.
func backendState() string {
	stStr, err := GetStatusJSON(false)
	if err != nil || len(stStr) == 0 {
		return ""
	}
	var st struct {
		BackendState string `json:"BackendState"`
	}
	if json.Unmarshal([]byte(stStr), &st) != nil {
		return ""
	}
	return st.BackendState
}

func ReUp() {
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if opt != nil && IsRunning() {
		if GetLoginURL() != "" {
			slog.Info("Login is in progress, skipping ReUp")
			return
		}
		if opt.AuthKey != "" {
			// Ask the daemon what it is doing first, the way the no-key path
			// below always has. Login here means POST /start, and Start drops
			// the control client to build another one: the node re-registers
			// and the map poll starts over. This runs on every trip to the
			// foreground, so an account with an auth key paid that on each one.
			// A key that actually changed does not come through here — it is
			// caught in ApplySettings, which logs in whatever the state.
			switch state := backendState(); state {
			case "Running", "Starting", "NeedsMachineAuth":
				slog.Debug("ReUp: the session is alive, leaving it alone", "backend_state", state)
			case "Stopped":
				// Logged in but paused, same as the no-key path: ask it to run
				// rather than log in again over a working node key.
				slog.Info("ReUp: logged in but stopped, requesting WantRunning")
				if err := PatchPrefsJSON(`{"WantRunning":true,"WantRunningSet":true}`); err != nil {
					slog.Warn("Could not request WantRunning", "err", err)
				}
			default:
				// NeedsLogin, NoState, or a status that could not be read.
				Login(opt.AuthKey)
			}
		} else {
			go registerMachineWithAuthKey(currentDaemonCtx(), opt)
		}
	}
}
