package appctr

import (
	"context"
	"log/slog"
	"os"
	"strings"
	"os/exec"
	"sync"
	"time"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
)

var stateMu sync.Mutex
var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc

type Closer interface {
	Close() error
}

type StartOptions struct {
	ExecPath      string
	SocketPath    string
	StatePath     string
	Socks5Server  string
	Socks5User    string
	Socks5Pass    string
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

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server, opt.HttpProxy, opt.Socks5User, opt.Socks5Pass)
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
			ctx, cancel := context.WithCancel(context.Background())
			stateMu.Lock()
			dnsProxyCancel = cancel
			stateMu.Unlock()

			fallbacks := []string{"8.8.8.8:53", "1.1.1.1:53"}
			if opt.DnsFallbacks != "" {
				fallbacks = strings.Split(opt.DnsFallbacks, ",")
			}

			doh := opt.DohFallback
			if doh == "" {
				doh = "https://1.1.1.1/dns-query"
			}

			if err := startDNSProxy(ctx, opt.DnsProxy, opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, fallbacks, doh); err != nil {
				slog.Error("DNS proxy error", "err", err)
			}
		}()
	}
}

func Stop() {
	stateMu.Lock()
	defer stateMu.Unlock()

	StopWebUI()

	if dnsProxyCancel != nil {
		dnsProxyCancel()
		dnsProxyCancel = nil
	}

	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		go func(p *os.Process) {
			time.Sleep(2 * time.Second)
			_ = p.Kill()
		}(cmd.Process)
		cmd = nil
	}
}

// --- Вспомогательные функции ---

func killLeftoverDaemons(daemonPath string) {
    // Простая команда для Android, чтобы прибить старые процессы
    _ = exec.Command("/system/bin/killall", "tailscaled").Run()
}

func StartWebUI(addr string) {
    slog.Info("Web UI start requested", "addr", addr)
    // Реализацию прокси для Web UI можно добавить сюда позже
}

func StopWebUI() {
    // Остановка Web UI
}