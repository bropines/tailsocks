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
	lastErrStr = "" // Очищаем после прочтения
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
	if strings.Contains(out, "https://login.tailscale.com/a/") {
		lines := strings.Split(out, "\n")
		for _, line := range lines {
			if strings.Contains(line, "https://login.tailscale.com/a/") {
				idx := strings.Index(line, "https://")
				if idx != -1 {
					return strings.TrimSpace(line[idx:])
				}
			}
		}
	}
	return ""
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	apiReady := false
	
	// Проверяем готовность сокета и API в тихом режиме
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(PC.Socket()); err == nil {
			out := RunTailscaleCmd("status")
			// Проверяем, что API ответило хоть чем-то осмысленным
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

	// Всего одна строка в логах, что мы начали настройку
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

	// Запускаем в фоне, чтобы не блокировать основной поток
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
