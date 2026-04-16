package appctr

import (
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"time"
)

func RunTailscaleCmd(commandStr string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	parts := strings.Fields(commandStr)
	args := append([]string{"--socket", PC.Socket()}, parts...)
	c := exec.Command(PC.Tailscale(), args...)
	output, err := c.CombinedOutput()
	if err != nil {
		// We don't use slog here to avoid writing to the log during every check.
		return string(output)
	}
	return string(output)
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

	args := []string{"--socket", PC.Socket(), "up", "--reset", "--timeout", "60s"}
	
	if opt.AuthKey != "" {
		args = append(args, "--auth-key", opt.AuthKey)
	}
	if opt.ExtraUpArgs != "" {
		args = append(args, strings.Fields(opt.ExtraUpArgs)...)
	}

	// Запускаем в фоне, чтобы не блокировать основной поток
	go func() {
		c := exec.Command(PC.Tailscale(), args...)
		output, err := c.CombinedOutput()
		if err != nil {
			// Логируем только если произошла реальная критическая ошибка (не таймаут)
			outStr := string(output)
			if strings.Contains(outStr, "invalid key") {
				slog.Error("Critical: Invalid Auth Key")
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
