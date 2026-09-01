package appctr

import (
	"encoding/json"
	"log/slog"
	"os/exec"
	"strings"
	"sync/atomic"
	"time"
)

var lastErrStr string

func GetLastError() string {
	e := lastErrStr
	lastErrStr = "" // Clear after reading
	return e
}

func ForceRefresh() {
	if !IsRunning() {
		return
	}
	slog.Debug("Manual refresh requested")
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err == nil && len(data) > 0 {
		var res struct {
			BackendState string `json:"BackendState"`
			AuthURL      string `json:"AuthURL"`
		}
		if json.Unmarshal(data, &res) == nil {
			busStateMu.Lock()
			if res.BackendState != "" {
				busState.BackendState = res.BackendState
			}
			busState.AuthURL = res.AuthURL
			busStateMu.Unlock()
		}
	}
	ReUp()
}

func RunTailscaleCmd(commandStr string) string {
	return RunTailscaleArgs(strings.Fields(commandStr)...)
}

func RunTailscaleArgs(parts ...string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
	}
	args := append([]string{"--socket", PC.Socket()}, parts...)
	c := exec.Command(PC.Tailscale(), args...)

	isRoutineCheck := len(parts) > 0 && (parts[0] == "status" || parts[0] == "dns" || parts[0] == "netcheck" || parts[0] == "ping")

	if !isRoutineCheck {
		slog.Info("Running Tailscale CLI", "args", parts)
	}

	output, err := c.CombinedOutput()
	outStr := string(output)

	if strings.Contains(outStr, "http 410") {
		lastErrStr = "410_GONE"
	}

	if err != nil {
		if !isRoutineCheck {
			slog.Error("CLI command failed", "out", outStr, "err", err)
		}
	} else if outStr != "" {
		if !isRoutineCheck {
			slog.Info("CLI command output", "out", outStr)
		}
	}

	return outStr
}

// registerMachineWithAuthKey waits for the daemon socket to be ready, then
// applies initial authentication and preferences via LocalAPI (CLI-free).
// registerInFlight keeps a single registration attempt alive at a time. Every
// app resume calls ForceRefresh → ReUp, which used to spawn another polling
// loop; several of them then raced and flooded the log with the same states.
var registerInFlight atomic.Bool

func registerMachineWithAuthKey(opt *StartOptions) {
	if !registerInFlight.CompareAndSwap(false, true) {
		slog.Debug("Registration already in progress, skipping duplicate")
		return
	}
	defer registerInFlight.Store(false)

	// Poll until the LocalAPI answers a real request, not just until the socket
	// file appears — the daemon binds the socket before it can serve traffic.
	apiReady := false
	for i := 1; i <= 20; i++ {
		if stStr, err := GetStatusJSON(false); err == nil && len(stStr) > 0 {
			apiReady = true
			break
		}
		time.Sleep(1 * time.Second)
	}

	if !apiReady {
		slog.Error("Tailscaled API timeout")
		return
	}

	if opt.DoReset {
		slog.Info("LocalAPI: reset requested, logging out existing session")
		Logout()
		time.Sleep(500 * time.Millisecond)
		slog.Info("LocalAPI: triggering interactive login for new session")
		_ = LoginInteractive()
		return
	}

	if opt.AuthKey != "" {
		slog.Info("LocalAPI: authenticating with auth key")
		// Preferences go through PATCH /prefs, which is a masked partial update.
		// Passing them as Start's UpdatePrefs would replace the whole prefs
		// object and silently drop everything not listed here — exit node,
		// advertised routes and tags among them.
		applyStartupPrefs(opt)
		payload, _ := json.Marshal(map[string]interface{}{"AuthKey": opt.AuthKey})
		_ = StartDaemon(string(payload))
		return
	}

	// Let the daemon reach its own verdict instead of guessing from an early state.
	//
	// tailscaled always begins in NoState. It only decides between Starting (a
	// saved session it can resume) and NeedsLogin (nothing to resume) once its
	// backend has been started and it has reached the control plane — behind a
	// control-plane proxy that easily takes several seconds. Treating that window
	// as "not logged in" and calling LoginInteractive regenerates the node key
	// and logs the device out of a perfectly good session.
	//
	// NoState also means the backend was never started at all, which is the
	// normal situation for a profile that has not been used yet. A bare Start
	// (no UpdatePrefs, so nothing is overwritten) makes the daemon commit to
	// NeedsLogin or Starting; only then is a login decision possible.
	var statusResp struct {
		BackendState string `json:"BackendState"`
		AuthURL      string `json:"AuthURL"`
	}

	const stateSettleTimeout = 45 * time.Second
	deadline := time.Now().Add(stateSettleTimeout)
	needsLogin := false
	startNudged := false
	lastLogged := ""

	for time.Now().Before(deadline) {
		stStr, err := GetStatusJSON(false)
		if err == nil && len(stStr) > 0 && json.Unmarshal([]byte(stStr), &statusResp) == nil {
			// Log transitions, not every poll: this loop runs twice a second and
			// used to bury the rest of the log while waiting for a login.
			if statusResp.BackendState != lastLogged {
				lastLogged = statusResp.BackendState
				slog.Info("Daemon startup state", "backend_state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")
			}

			switch statusResp.BackendState {
			case "Running", "Starting":
				slog.Info("Account has an active session, preserving it", "backend_state", statusResp.BackendState)
				return
			case "Stopped":
				// Logged in but paused. Ask it to run; logging in again would
				// throw away a working node key for no reason.
				slog.Info("Account is logged in but stopped, requesting WantRunning")
				_ = PatchPrefsJSON(`{"WantRunning":true,"WantRunningSet":true}`)
				return
			case "NeedsLogin":
				if statusResp.AuthURL != "" {
					slog.Info("Daemon already produced an auth URL, nothing to trigger")
					return
				}
				needsLogin = true
			case "NoState":
				if !startNudged {
					startNudged = true
					slog.Info("Backend has not been started yet, sending Start")
					applyStartupPrefs(opt)
					_ = StartDaemon("{}")
				}
			}
			if needsLogin {
				break
			}
			if statusResp.AuthURL != "" {
				return
			}
		}
		time.Sleep(500 * time.Millisecond)
	}

	if !needsLogin {
		// Still undecided after the timeout: the daemon is stuck, not logged out.
		// Forcing a login here would throw away the node key.
		slog.Warn("Daemon did not settle into a known state, not forcing a login",
			"backend_state", statusResp.BackendState)
		return
	}

	slog.Info("Account requires authentication (BackendState: " + statusResp.BackendState + "). Triggering interactive login.")
	_ = LoginInteractive()
}

// applyStartupPrefs pushes the options the user configured through the masked
// PATCH /prefs endpoint. Start's UpdatePrefs replaces the entire prefs object,
// so it must never be used to carry a partial set.
func applyStartupPrefs(opt *StartOptions) {
	prefs := map[string]interface{}{
		"WantRunning":    true,
		"WantRunningSet": true,
		"RouteAll":       opt.AcceptRoutes,
		"RouteAllSet":    true,
		"CorpDNS":        opt.AcceptDNS,
		"CorpDNSSet":     true,
	}
	if opt.Hostname != "" {
		prefs["Hostname"] = opt.Hostname
		prefs["HostnameSet"] = true
	}
	if opt.LoginServer != "" {
		prefs["ControlURL"] = opt.LoginServer
		prefs["ControlURLSet"] = true
	}
	payload, _ := json.Marshal(prefs)
	if err := PatchPrefsJSON(string(payload)); err != nil {
		slog.Warn("Could not apply startup preferences", "err", err)
	}
}
