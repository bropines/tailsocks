package appctr

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"os/exec"
	"sync"
	"time"
	"net/http"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
	"tailscale.com/client/local"
	"tailscale.com/client/web"
)

var latestInterfaceState string
var stateMu sync.Mutex

func InjectNetworkState(jsonState string) {
	stateMu.Lock()
	latestInterfaceState = jsonState
	stateMu.Unlock()
	
	// Currently, InjectEvent cannot be called directly here as we lack a Monitor reference.
	// The core is patched to use latestInterfaceState during getter calls.
	slog.Info("Network state injected from Kotlin")
}
var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc
var lastOptions *StartOptions
var webServer *http.Server
var coreVersion string = "unknown"

func GetCoreVersion() string {
	return coreVersion
}

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
	TaildropDir   string
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

func GetLoginURLString() string {
	return GetLoginURL()
}

func ApplySettings(opt *StartOptions) {
	stateMu.Lock()
	old := lastOptions
	stateMu.Unlock()

	// Start if not already running
	if !IsRunning() {
		slog.Info("Tailscaled not running, performing full start")
		Start(opt)
		return
	}

	// CRITICAL FIX: If login is in progress (Login URL present),
	// block configuration updates to prevent session resets and 410 Gone errors.
	if GetLoginURL() != "" {
		slog.Info("Login in progress, ignoring ApplySettings to protect session")
		return
	}

	// Initialize options if they don't exist
	if old == nil {
		stateMu.Lock()
		lastOptions = opt
		stateMu.Unlock()
		ReUp()
		return
	}

	// 1. Critical parameter check.
	// Force a full restart if core paths or proxy settings changed.
	if old.Socks5Server != opt.Socks5Server ||
		old.HttpProxy != opt.HttpProxy ||
		old.Socks5User != opt.Socks5User ||
		old.Socks5Pass != opt.Socks5Pass ||
		old.StatePath != opt.StatePath {
		slog.Info("Critical settings or account changed, performing full restart")
		Start(opt)
		return
	}

	// 2. Update option cache
	stateMu.Lock()
	lastOptions = opt
	stateMu.Unlock()

	// 3. DNS-only restart if only DNS parameters changed.
	if old.DnsProxy != opt.DnsProxy ||
		old.DnsFallbacks != opt.DnsFallbacks ||
		old.DohFallback != opt.DohFallback {
		slog.Info("DNS settings changed, restarting DNS proxy only")
		RestartDNS()
	}

	// 4. Synchronize other settings (hostname, tags, etc.) via ReUp.
	// This preserves the current session and waits for Netmap synchronization.
	ReUp()
}

func ReUp() {
	stateMu.Lock()
	opt := lastOptions
	pc := PC
	stateMu.Unlock()

	if opt != nil && IsRunning() {
		// If we are already waiting for login, don't trigger 'up' again
		// as it might reset the pending session and cause 410 Gone.
		if GetLoginURL() != "" {
			slog.Info("Login is in progress, skipping ReUp to avoid 410 Gone")
			return
		}
		go registerMachineWithAuthKey(pc, opt)
	}
}

func Start(opt *StartOptions) {
	Stop()
	time.Sleep(1 * time.Second)

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	lastOptions = opt
	stateMu.Unlock()

	killLeftoverDaemons(PC.Tailscaled())

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server, opt.HttpProxy, opt.Socks5User, opt.Socks5Pass, opt.TaildropDir)
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
		RestartDNS()
	}
}

func RestartDNS() {
	stateMu.Lock()
	opt := lastOptions
	if dnsProxyCancel != nil {
		dnsProxyCancel()
		dnsProxyCancel = nil
	}
	stateMu.Unlock()

	if opt == nil || opt.DnsProxy == "" {
		return
	}

	go func() {
		// Allow time for the previous proxy to release the socket
		time.Sleep(500 * time.Millisecond)

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

		slog.Info("Starting DNS proxy", "addr", opt.DnsProxy)
		if err := startDNSProxy(ctx, opt.DnsProxy, opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, fallbacks, doh); err != nil {
			slog.Error("DNS proxy error", "err", err)
		}
	}()
}

func Stop() {
	StopWebUI()

	stateMu.Lock()
	defer stateMu.Unlock()

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

// --- Helper Functions ---

func killLeftoverDaemons(daemonPath string) {
    // Utility command for Android to terminate orphaned processes
    _ = exec.Command("/system/bin/killall", "tailscaled").Run()
}

func StartWebUI(addr string) {
	stateMu.Lock()
	if webServer != nil {
		stateMu.Unlock()
		return
	}
	pc := PC
	stateMu.Unlock()

	slog.Info("Web UI start requested", "addr", addr)

	lc := &local.Client{
		Socket: pc.Socket(),
		UseSocketOnly: true,
	}

	ws, err := web.NewServer(web.ServerOpts{
		Mode:        web.LoginServerMode,
		LocalClient: lc,
		Logf: func(format string, args ...any) {
			slog.Info("web", "msg", fmt.Sprintf(format, args...))
		},
	})
	if err != nil {
		slog.Error("Failed to create web server", "err", err)
		return
	}

	stateMu.Lock()
	webServer = &http.Server{
		Addr:    addr,
		Handler: ws,
	}
	stateMu.Unlock()

	go func() {
		slog.Info("Web UI listening", "addr", addr)
		if err := webServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Web UI listen error", "err", err)
		}
		stateMu.Lock()
		webServer = nil
		stateMu.Unlock()
	}()
}

func StopWebUI() {
	stateMu.Lock()
	ws := webServer
	webServer = nil
	stateMu.Unlock()

	if ws != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = ws.Shutdown(ctx)
		slog.Info("Web UI stopped")
	}
}