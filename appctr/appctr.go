package appctr

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
)

var stateMu sync.Mutex
var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc
var webCmd *exec.Cmd

type Closer interface {
	Close() error
}

type StartOptions struct {
	ExecPath      string
	SocketPath    string
	StatePath     string
	Socks5Server  string
	HttpProxy     string
	CloseCallBack Closer
	AuthKey       string
	ExtraUpArgs   string
	DnsProxy      string
	DnsFallbacks  string
	DohFallback   string
	DoReset       bool 
	EnableWebUI   bool  
	WebUIAddr     string
}

func SetLogLevel(level int32) {
	stateMu.Lock()
	defer stateMu.Unlock()
	currentLogLevel = level
}

func logWithFilter(text string) {
	stateMu.Lock()
	lvl := currentLogLevel
	stateMu.Unlock()

	if lvl >= 1 {
		lower := strings.ToLower(text)
		if strings.Contains(lower, "magicsock") ||
			strings.Contains(lower, "netcheck") ||
			strings.Contains(lower, "ratelimit") ||
			strings.Contains(lower, "udp proxy: received") ||
			strings.Contains(lower, "logtail") {
			return
		}
	}
	slog.Info(text)
}

func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return cmd != nil && cmd.Process != nil
}

// killLeftoverDaemons убивает зависшие процессы tailscaled от предыдущего запуска.
func killLeftoverDaemons(daemonPath string) {
	slog.Info("Killing leftover tailscaled processes", "path", daemonPath)
	_ = exec.Command("/system/bin/pkill", "-f", daemonPath).Run()
	_ = exec.Command("/system/bin/pkill", "tailscaled").Run()
	time.Sleep(600 * time.Millisecond)
}

func Start(opt *StartOptions) {
	Stop()
	time.Sleep(1 * time.Second)

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	stateMu.Unlock()

	killLeftoverDaemons(PC.Tailscaled())

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}
	if opt.HttpProxy == "" {
		opt.HttpProxy = "127.0.0.1:1057"
	}

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server, opt.HttpProxy)
		if err != nil {
			slog.Error("tailscaled cmd crashed", "err", err)
		}
		Stop()
		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	go registerMachineWithAuthKey(PC, opt)

	if opt.DnsProxy != "" {
		go func() {
			time.Sleep(5 * time.Second)
			slog.Info("Starting DNS proxy", "addr", opt.DnsProxy)

			ctx, cancel := context.WithCancel(context.Background())
			stateMu.Lock()
			dnsProxyCancel = cancel
			stateMu.Unlock()

			var fallbacks []string
			if opt.DnsFallbacks != "" {
				for _, f := range strings.Split(opt.DnsFallbacks, ",") {
					f = strings.TrimSpace(f)
					if f != "" {
						fallbacks = append(fallbacks, f)
					}
				}
			}
			if len(fallbacks) == 0 {
				fallbacks = []string{"8.8.8.8:53", "1.1.1.1:53"}
			}

			doh := opt.DohFallback
			if doh == "" {
				doh = "https://1.1.1.1/dns-query"
			}

			if err := startDNSProxy(ctx, opt.DnsProxy, opt.Socks5Server, fallbacks, doh); err != nil {
				slog.Error("DNS proxy stopped", "err", err)
			}
		}()
	}
}

func Stop() {
	stateMu.Lock()
	defer stateMu.Unlock()

	StopWebUI()

	if dnsProxyCancel != nil {
		slog.Info("stop dns proxy")
		dnsProxyCancel()
		dnsProxyCancel = nil
	}

	x := cmd
	cmd = nil

	if x != nil && x.Process != nil {
		slog.Info("stop tailscaled cmd")
		_ = x.Process.Signal(syscall.SIGTERM)
		go func(p *os.Process) {
			time.Sleep(2 * time.Second)
			_ = p.Kill()
		}(x.Process)
	}
}

func RunTailscaleCmd(commandStr string) string {
	if !IsRunning() {
		return "Error: Tailscaled service is not running."
	}

	parts := strings.Fields(commandStr)
	if len(parts) == 0 {
		return ""
	}

	args := []string{"--socket", PC.Socket()}
	args = append(args, parts...)

	c := exec.Command(PC.Tailscale(), args...)
	output, err := c.CombinedOutput()

	result := string(output)
	if err != nil {
		result += fmt.Sprintf("\nError: %v", err)
	}
	return result
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	// 1. Сначала просто ждем появления сокета (до 15 секунд)
	socketReady := false
	for i := 0; i < 15; i++ {
		if _, err := os.Stat(PC.Socket()); err == nil {
			socketReady = true
			break
		}
		time.Sleep(1 * time.Second)
	}

	if !socketReady {
		slog.Error("Tailscaled socket never appeared")
		return
	}

	slog.Info("Socket found, waiting 1s for daemon to be ready...")
	time.Sleep(1 * time.Second)

	// 2. Пробуем выполнить команду up (до 3 попыток)
	for attempt := 1; attempt <= 3; attempt++ {
		// Жестко захардкоженный --reset для обхода дедлоков Android
		args := []string{"--socket", PC.Socket(), "up", "--reset", "--timeout", "30s"}

		if opt.AuthKey != "" {
			args = append(args, "--auth-key", opt.AuthKey)
		}
		if opt.ExtraUpArgs != "" {
			args = append(args, strings.Fields(opt.ExtraUpArgs)...)
		}

		slog.Info("Running tailscale up", "attempt", attempt)
		
		c := exec.Command(PC.Tailscale(), args...)
		data, err := c.CombinedOutput()
		output := string(data)

		// УСПЕШНОЕ ПОДКЛЮЧЕНИЕ
		if err == nil {
			slog.Info("tailscale up success", "output", output)
			
			// Стартуем Web UI, если галочка включена
			if opt.EnableWebUI {
				StartWebUI(opt.WebUIAddr)
			}
			return // Выходим, так как подключились
		}

		// ЕСЛИ ОШИБКА
		slog.Info("tailscale up failed", "output", output, "err", err)
		
		if strings.Contains(output, "invalid key") || strings.Contains(output, "API key does not exist") {
			slog.Error("Critical Auth Error: Invalid Auth Key. Please check settings.")
			return
		}

		slog.Info("Retrying tailscale up in 5 seconds...")
		time.Sleep(5 * time.Second)
	}

	slog.Info("Daemon configured but slow to connect. Leaving it to finish in background.")
}

func StartWebUI(listenAddr string) {
	StopWebUI()
	if listenAddr == "" {
		listenAddr = "127.0.0.1:8080"
	}
	slog.Info("Starting Web UI", "addr", listenAddr)
	
	args := []string{"--socket", PC.Socket(), "web", "--listen", listenAddr}
	c := exec.Command(PC.Tailscale(), args...)
	webCmd = c
	
	go func() {
		err := c.Run()
		if err != nil {
			slog.Error("Web UI stopped", "err", err)
		}
	}()
}

func StopWebUI() {
	if webCmd != nil && webCmd.Process != nil {
		slog.Info("Stopping Web UI")
		_ = webCmd.Process.Signal(syscall.SIGTERM)
		go func(p *os.Process) {
			time.Sleep(1 * time.Second)
			_ = p.Kill()
		}(webCmd.Process)
		webCmd = nil
	}
}