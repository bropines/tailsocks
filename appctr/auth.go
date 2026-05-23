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
	apiReady := false
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(pc.Socket()); err == nil {
			data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
			if err == nil && len(data) > 0 {
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

	slog.Info("Daemon is ready, applying configuration...")

	if opt.DoReset {
		// Logout clears existing state; daemon will enter NeedsLogin.
		slog.Info("LocalAPI: reset requested, logging out")
		Logout()
		time.Sleep(500 * time.Millisecond)
	}

	if opt.AuthKey != "" {
		slog.Info("LocalAPI: authenticating with auth key")
		go func() {
			payload, _ := json.Marshal(map[string]string{"AuthKey": opt.AuthKey})
			_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(string(payload)))
			if err != nil {
				if strings.Contains(err.Error(), "invalid") {
					slog.Error("Critical: Invalid Auth Key", "err", err)
				} else {
					slog.Error("LocalAPI: /start failed", "err", err)
				}
			} else {
				slog.Info("LocalAPI: authentication request sent")
			}
		}()
	}
}
