package appctr

import (
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
		return "Error: Tailscaled is not running."
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

func GetLoginURL() string {
	out := RunTailscaleCmd("status")
	// Tailscale status output usually contains the login URL in a line starting with https://
	// or after "To authenticate, visit:"
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.Contains(trimmed, "https://") {
			// Look for the start of the URL
			idx := strings.Index(trimmed, "https://")
			if idx != -1 {
				// Potential URL found. Check if it's likely an auth URL.
				// In CLI output, it's often the only URL or follows a specific prompt.
				urlPart := trimmed[idx:]
				// Split by space to get just the URL if there's trailing text
				fields := strings.Fields(urlPart)
				if len(fields) > 0 {
					return fields[0]
				}
			}
		}
	}
	return ""
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	apiReady := false
	
	// Poll socket and API readiness in silent mode
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(PC.Socket()); err == nil {
			out := RunTailscaleCmd("status")
			// Ensure the API is responsive
			if !strings.Contains(out, "failed to connect") && !strings.Contains(out, "not running") {
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

	// Single log entry to indicate configuration start
	slog.Info("Daemon is ready, applying configuration...")

	args := []string{"--socket", PC.Socket(), "up", "--timeout", "60s"}
	
	if opt.DoReset {
		args = append(args, "--reset")
	}

	if opt.AuthKey != "" {
		args = append(args, "--auth-key", opt.AuthKey)
	} else if opt.DoReset {
		// If we are explicitly resetting and have no auth key, 
		// we likely want a fresh interactive login.
		args = append(args, "--force-reauth")
	}

	if opt.ExtraUpArgs != "" {
		args = append(args, strings.Fields(opt.ExtraUpArgs)...)
	}

	// Execute in background to avoid blocking the main thread
	go func() {
		c := exec.Command(PC.Tailscale(), args...)
		output, err := c.CombinedOutput()
		outStr := string(output)
		if err != nil {
			if strings.Contains(outStr, "invalid key") {
				slog.Error("Critical: Invalid Auth Key")
			} else {
				slog.Error("Tailscale up failed", "err", err, "out", outStr)
			}
		} else {
			slog.Info("Tailscale configuration applied successfully")
		}
	}()

	if opt.EnableWebUI {
		StartWebUI(opt.WebUIAddr)
	} else {
		StopWebUI()
	}
}
