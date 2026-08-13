package appctr

import (
	"encoding/json"
	"log/slog"
	"os"
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
func registerMachineWithAuthKey(pc pathControl, opt *StartOptions) {
	// Poll until socket exists and LocalAPI responds.
	var stStr string
	apiReady := false
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(pc.Socket()); err == nil {
			var err error
			stStr, err = GetStatusJSON(false)
			if err == nil && len(stStr) > 0 {
				apiReady = true
				break
			}
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

	// Poll status for 1.5s (5 x 300ms) to allow tailscaled to evaluate saved state from disk naturally.
	var statusResp struct {
		BackendState string `json:"BackendState"`
		AuthURL      string `json:"AuthURL"`
	}

	for i := 0; i < 5; i++ {
		stStr, err := GetStatusJSON(false)
		if err == nil && len(stStr) > 0 {
			if json.Unmarshal([]byte(stStr), &statusResp) == nil {
				slog.Debug("Daemon startup state poll", "attempt", i+1, "backend_state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")
				if statusResp.BackendState == "Running" {
					slog.Debug("Account is already logged in (BackendState: Running). Preserving active session.")
					return
				}
				if statusResp.BackendState == "NeedsLogin" || statusResp.AuthURL != "" {
					slog.Info("Daemon needs login", "backend_state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")
					break
				}
			}
		}
		time.Sleep(300 * time.Millisecond)
	}

	if statusResp.BackendState == "Running" {
		slog.Debug("Account is already logged in (BackendState: Running). Preserving active session.")
		return
	}

	// Account is NOT logged in (BackendState == "NeedsLogin" or "NoState").
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
