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
	slog.Info("Manual refresh requested")
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
	var statusData []byte
	apiReady := false
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(pc.Socket()); err == nil {
			data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
			if err == nil && len(data) > 0 {
				statusData = data
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

	slog.Info("Daemon is ready, checking backend state...")

	var statusResp struct {
		BackendState string `json:"BackendState"`
		AuthURL      string `json:"AuthURL"`
	}
	_ = json.Unmarshal(statusData, &statusResp)

	slog.Info("Backend status on startup", "state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")

	if opt.DoReset {
		// Logout clears existing state; daemon will enter NeedsLogin.
		slog.Info("LocalAPI: reset requested, logging out")
		Logout()
		time.Sleep(500 * time.Millisecond)
		statusResp.BackendState = "NeedsLogin"
		statusResp.AuthURL = ""
	}

	// IF backend is already Running or Starting (and has no AuthURL required),
	// the account is ALREADY logged in from saved state storage.
	// DO NOT force /start or /login-interactive, as that would invalidate the existing session.
	if statusResp.BackendState == "Running" || (statusResp.BackendState == "Starting" && statusResp.AuthURL == "") {
		slog.Info("Account is already logged in (state: " + statusResp.BackendState + "), preserving active session.")
		return
	}

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
		slog.Info("LocalAPI: setting custom ControlURL in /start", "controlURL", opt.LoginServer)
		updatePrefs["ControlURL"] = opt.LoginServer
		updatePrefs["ControlURLSet"] = true
	}

	startOpts := map[string]interface{}{
		"AuthKey":     opt.AuthKey,
		"UpdatePrefs": updatePrefs,
	}

	payload, _ := json.Marshal(startOpts)
	slog.Info("LocalAPI: initializing login session with /start", "has_authkey", opt.AuthKey != "")
	_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(string(payload)))
	if err != nil {
		if strings.Contains(err.Error(), "invalid") {
			slog.Error("Critical: Invalid Auth Key / Start error", "err", err)
		} else {
			slog.Error("LocalAPI: /start failed", "err", err)
		}
	} else {
		slog.Info("LocalAPI: /start request sent successfully")
	}

	if opt.AuthKey == "" && statusResp.AuthURL == "" {
		slog.Info("LocalAPI: triggering interactive login for AuthURL")
		time.Sleep(300 * time.Millisecond)
		_, err := doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
		if err != nil {
			slog.Error("LocalAPI: /login-interactive failed", "err", err)
		} else {
			slog.Info("LocalAPI: /login-interactive requested successfully")
		}

		// Poll status up to 15 times (4.5s) for AuthURL generation
		for i := 0; i < 15; i++ {
			time.Sleep(300 * time.Millisecond)
			sData, err := doLocalRequest("GET", "/localapi/v0/status", nil)
			if err == nil {
				var st struct {
					AuthURL string `json:"AuthURL"`
				}
				if json.Unmarshal(sData, &st) == nil && st.AuthURL != "" {
					slog.Info("LocalAPI: AuthURL generated successfully", "url", st.AuthURL)
					break
				}
			}
			if i == 5 {
				slog.Info("LocalAPI: re-triggering login-interactive for Headscale/Tailscale")
				_, _ = doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
			}
		}
	}
}
