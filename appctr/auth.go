package appctr

import (
	"fmt"
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
		return fmt.Sprintf("%s\nError: %v", string(output), err)
	}
	return string(output)
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	slog.Info("Waiting for daemon API to wake up...")
	apiReady := false
	
	// Ждем, пока локальный сокет не начнет отвечать без ошибок (до 20 секунд)
	for i := 1; i <= 20; i++ {
		if _, err := os.Stat(PC.Socket()); err == nil {
			out := RunTailscaleCmd("status")
			if !strings.Contains(out, "failed to connect") && !strings.Contains(out, "not running") {
				apiReady = true
				break
			}
		}
		time.Sleep(1 * time.Second)
	}

	if !apiReady {
		slog.Error("CRITICAL: Daemon API never responded. Cannot start tunnel.")
		return
	}

	// Формируем команду. Даем ей 60 секунд!
	args := []string{"--socket", PC.Socket(), "up", "--reset", "--timeout", "60s"}
	
	if opt.AuthKey != "" {
		args = append(args, "--auth-key", opt.AuthKey)
	}
	if opt.ExtraUpArgs != "" {
		args = append(args, strings.Fields(opt.ExtraUpArgs)...)
	}

	slog.Info("Sending configuration to daemon...", "cmd", strings.Join(args, " "))

	// Запускаем `tailscale up` В ФОНЕ, чтобы не тормозить запуск Web UI и DNS прокси
	go func() {
		c := exec.Command(PC.Tailscale(), args...)
		output, err := c.CombinedOutput()
		outStr := string(output)

		if err != nil {
			// Если таймаут все же сработал (интернет совсем тупит), демон всё равно 
			// продолжит работу в фоне и подключится, как только появится сеть.
			slog.Warn("tailscale up finished with error or timeout, but daemon is still working", "err", err, "out", outStr)
			if strings.Contains(outStr, "invalid key") {
				slog.Error("CRITICAL: Invalid Auth Key!")
			}
		} else {
			slog.Info("tailscale up configuration applied successfully!")
		}
	}()

	// Просто для удобства — выведем статус в логи через 15 секунд
	go func() {
		time.Sleep(15 * time.Second)
		out := RunTailscaleCmd("status")
		slog.Info("Current Tunnel Status", "status", strings.ReplaceAll(out, "\n", " | "))
	}()

	// Запускаем локальную веб-панель (если включена)
	if opt.EnableWebUI {
		StartWebUI(opt.WebUIAddr)
	}
}