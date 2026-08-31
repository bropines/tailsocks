package appctr

import (
	"encoding/json"
	"log/slog"
	"os/exec"
	"strings"
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
func registerMachineWithAuthKey(opt *StartOptions) {
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
		updatePrefs := map[string]interface{}{
			"WantRunning": true,
			"RouteAll":    opt.AcceptRoutes,
			"RouteAllSet": true,
			"CorpDNS":     opt.AcceptDNS,
			"CorpDNSSet":  true,
		}
		if opt.Hostname != "" {
			updatePrefs["Hostname"] = opt.Hostname
			updatePrefs["HostnameSet"] = true
		}
		if opt.LoginServer != "" {
			updatePrefs["ControlURL"] = opt.LoginServer
			updatePrefs["ControlURLSet"] = true
		}
		startOpts := map[string]interface{}{
			"AuthKey":     opt.AuthKey,
			"UpdatePrefs": updatePrefs,
		}
		payload, _ := json.Marshal(startOpts)
		_ = StartDaemon(string(payload))
		return
	}

	// Wait for the daemon's own verdict instead of guessing from an early state.
	//
	// tailscaled always starts in NoState and only decides between Starting (a
	// saved session it can resume) and NeedsLogin (nothing to resume) once it has
	// reached the control plane. Behind a control-plane proxy that easily takes
	// several seconds. Treating that window as "not logged in" and calling
	// LoginInteractive regenerates the node key and logs the device out of a
	// perfectly good session — which showed up as "did not connect on the first
	// try, worked on the second".
	var statusResp struct {
		BackendState string `json:"BackendState"`
		AuthURL      string `json:"AuthURL"`
	}

	const stateSettleTimeout = 45 * time.Second
	deadline := time.Now().Add(stateSettleTimeout)
	needsLogin := false

	for time.Now().Before(deadline) {
		stStr, err := GetStatusJSON(false)
		if err == nil && len(stStr) > 0 && json.Unmarshal([]byte(stStr), &statusResp) == nil {
			slog.Debug("Daemon startup state poll", "backend_state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")

			switch statusResp.BackendState {
			case "Running", "Starting":
				// A saved session is being resumed; never interrupt it.
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
		// Still undecided after the timeout. The daemon is stuck rather than
		// logged out — forcing a login here would throw away the node key.
		slog.Warn("Daemon did not settle into a known state, not forcing a login",
			"backend_state", statusResp.BackendState)
		return
	}

	// The daemon itself reports NeedsLogin.
	// Configure custom ControlURL (Headscale) if specified, then trigger LoginInteractive.
	if opt.LoginServer != "" {
		slog.Info("LocalAPI: configuring custom ControlURL before LoginInteractive", "url", opt.LoginServer)
		updatePrefs := map[string]interface{}{
			"ControlURL":    opt.LoginServer,
			"ControlURLSet": true,
			"WantRunning":   true,
		}
		if opt.Hostname != "" {
			updatePrefs["Hostname"] = opt.Hostname
			updatePrefs["HostnameSet"] = true
		}
		startOpts := map[string]interface{}{
			"UpdatePrefs": updatePrefs,
		}
		payload, _ := json.Marshal(startOpts)
		_ = StartDaemon(string(payload))
		time.Sleep(300 * time.Millisecond)
	}

	slog.Info("Account requires authentication (BackendState: " + statusResp.BackendState + "). Triggering interactive login.")
	_ = LoginInteractive()
}
