package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/client/local"
	"tailscale.com/client/web"
)

var latestInterfaceState string
var stateMu sync.Mutex

type GlobalConfig struct {
	mu         sync.RWMutex
	Socks5Addr string
	Socks5User string
	Socks5Pass string
	DNSAddr    string
}

func (c *GlobalConfig) get() (socks, user, pass, dns string) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.Socks5Addr, c.Socks5User, c.Socks5Pass, c.DNSAddr
}

func (c *GlobalConfig) update(socks, user, pass, dns string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Socks5Addr = socks
	c.Socks5User = user
	c.Socks5Pass = pass
	c.DNSAddr = dns
}

var GConfig GlobalConfig

func InjectNetworkState(jsonState string) {
	stateMu.Lock()
	latestInterfaceState = jsonState
	stateMu.Unlock()
	slog.Info("Network state injected from Kotlin")
}

var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc
var taildropCancel context.CancelFunc
var lastOptions *StartOptions
var webServer *http.Server
var coreVersion string = "unknown"

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
	ControlProxy  string
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
	Hostname      string
	AcceptRoutes  bool
	AcceptDNS     bool
	ExitNodeID    string
}

var nullOptions = &StartOptions{}

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

// --- JNI Exported Functions (Static methods in Appctr class) ---

func NativeDnsQuery(domain, qtype string) string {
	slog.Info("LocalAPI: DNS Query", "domain", domain, "type", qtype)
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}

	var msg dnsmessage.Message
	msg.Header.ID = 0x1234
	msg.Header.RecursionDesired = true

	t := dnsmessage.TypeA
	if strings.ToUpper(qtype) == "AAAA" {
		t = dnsmessage.TypeAAAA
	}

	name, err := dnsmessage.NewName(domain + ".")
	if err != nil {
		return "Invalid domain"
	}

	msg.Questions = []dnsmessage.Question{{Name: name, Type: t, Class: dnsmessage.ClassINET}}
	query, _ := msg.Pack()

	fallbacks := []string{"8.8.8.8:53", "1.1.1.1:53"}
	doh := ""
	if opt != nil {
		if opt.DnsFallbacks != "" {
			fallbacks = strings.Split(opt.DnsFallbacks, ",")
		}
		doh = opt.DohFallback
	}

	resp := processDNSQuery(query, fallbacks, doh)
	if resp == nil {
		return "No response"
	}

	var respMsg dnsmessage.Message
	if err := respMsg.Unpack(resp); err != nil {
		return "Error unpacking: " + err.Error()
	}

	if len(respMsg.Answers) == 0 {
		return "No answers (RCODE: " + respMsg.Header.RCode.String() + ")"
	}

	var results []string
	for _, ans := range respMsg.Answers {
		switch b := ans.Body.(type) {
		case *dnsmessage.AResource:
			results = append(results, net.IP(b.A[:]).String())
		case *dnsmessage.AAAAResource:
			results = append(results, net.IP(b.AAAA[:]).String())
		case *dnsmessage.CNAMEResource:
			results = append(results, b.CNAME.String())
		default:
			results = append(results, "Unknown record type")
		}
	}
	return strings.Join(results, "\n")
}

func FlushDNS() {
	dnsCache.Range(func(key, value interface{}) bool {
		dnsCache.Delete(key)
		return true
	})
	slog.Info("DNS cache flushed")
}

func logSettings(opt *StartOptions) {
	if opt == nil {
		return
	}
	
	exitNodeStatus := "Disabled"
	if opt.ExitNodeID != "" {
		exitNodeStatus = "Enabled"
	}

	authKeyStatus := "Empty"
	if opt.AuthKey != "" {
		authKeyStatus = "Present"
	}

	slog.Info("\n--- Settings ---\n" +
		fmt.Sprintf("hostname: %s\n", opt.Hostname) +
		fmt.Sprintf("socks5: %s\n", opt.Socks5Server) +
		fmt.Sprintf("httpProxy: %s\n", opt.HttpProxy) +
		fmt.Sprintf("dnsProxy: %s\n", opt.DnsProxy) +
		fmt.Sprintf("acceptRoutes: %v\n", opt.AcceptRoutes) +
		fmt.Sprintf("acceptDNS: %v\n", opt.AcceptDNS) +
		fmt.Sprintf("exitNode: %s\n", exitNodeStatus) +
		fmt.Sprintf("authKey: %s\n", authKeyStatus) +
		"----------------")
}

func syncSettings(opt *StartOptions) {
	if opt == nil {
		return
	}
	go func() {
		// Wait for socket to be ready
		for i := 0; i < 20; i++ {
			if _, err := os.Stat(opt.SocketPath); err == nil {
				break
			}
			time.Sleep(500 * time.Millisecond)
		}

		time.Sleep(500 * time.Millisecond)
		prefs := make(map[string]interface{})
		if opt.Hostname != "" {
			prefs["Hostname"] = opt.Hostname
			prefs["HostnameSet"] = true
		}
		prefs["RouteAll"] = opt.AcceptRoutes
		prefs["RouteAllSet"] = true
		prefs["CorpDNS"] = opt.AcceptDNS
		prefs["CorpDNSSet"] = true
		prefs["ExitNodeID"] = opt.ExitNodeID
		prefs["ExitNodeIDSet"] = true
		prefs["ExitNodeIP"] = ""
		prefs["ExitNodeIPSet"] = true
		prefs["WantRunning"] = true
		prefs["WantRunningSet"] = true

		jsonData, _ := json.Marshal(prefs)
		slog.Info("Syncing settings via LocalAPI", "payload", string(jsonData))
		SetPrefs(string(jsonData))
	}()
}

func ApplySettings(opt *StartOptions) {
	stateMu.Lock()
	old := lastOptions
	stateMu.Unlock()

	if !IsRunning() {
		slog.Info("Tailscaled not running, performing full start")
		Start(opt)
		return
	}

	if GetLoginURL() != "" {
		slog.Info("Login in progress, ignoring ApplySettings to protect session")
		return
	}

	if old == nil {
		stateMu.Lock()
		lastOptions = opt
		stateMu.Unlock()
		ReUp()
		syncSettings(opt)
		return
	}

	if old.Socks5Server != opt.Socks5Server ||
		old.HttpProxy != opt.HttpProxy ||
		old.ControlProxy != opt.ControlProxy ||
		old.Socks5User != opt.Socks5User ||
		old.Socks5Pass != opt.Socks5Pass ||
		old.StatePath != opt.StatePath {
		slog.Info("Critical settings or account changed, performing full restart")
		Start(opt)
		return
	}

	stateMu.Lock()
	lastOptions = opt
	stateMu.Unlock()
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

	if old.DnsProxy != opt.DnsProxy ||
		old.DnsFallbacks != opt.DnsFallbacks ||
		old.DohFallback != opt.DohFallback {
		slog.Info("DNS settings changed, restarting DNS proxy only")
		RestartDNS()
	}

	if opt.AuthKey != "" && opt.AuthKey != old.AuthKey {
		slog.Info("AuthKey changed, triggering Login via LocalAPI")
		Login(opt.AuthKey)
		return
	}

	if opt.DoReset {
		slog.Info("Reset requested via Logout")
		Logout()
		return
	}

	logSettings(opt)
	syncSettings(opt)
}

func ReUp() {
	stateMu.Lock()
	opt := lastOptions
	pc := PC
	stateMu.Unlock()

	if opt != nil && IsRunning() {
		if GetLoginURL() != "" {
			slog.Info("Login is in progress, skipping ReUp")
			return
		}
		if opt.AuthKey != "" {
			Login(opt.AuthKey)
		} else {
			go registerMachineWithAuthKey(pc, opt)
		}
	}
}

func Start(opt *StartOptions) {
	Stop()
	time.Sleep(1 * time.Second)

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	lastOptions = opt
	stateMu.Unlock()
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

	killLeftoverDaemons(PC.Tailscaled())

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server, opt.HttpProxy, opt.Socks5User, opt.Socks5Pass, opt.TaildropDir, opt.ControlProxy)
		if err != nil {
			slog.Error("tailscaled cmd crashed", "err", err)
		}
		Stop()
		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	go func() {
		// Wait for socket
		for i := 0; i < 20; i++ {
			if _, err := os.Stat(opt.SocketPath); err == nil {
				break
			}
			time.Sleep(500 * time.Millisecond)
		}
		if opt.AuthKey != "" {
			Login(opt.AuthKey)
		} else {
			registerMachineWithAuthKey(PC, opt)
		}
		syncSettings(opt)
	}()

	if opt.DnsProxy != "" {
		RestartDNS()
	}

	if opt.TaildropDir != "" {
		stateMu.Lock()
		ctx, cancel := context.WithCancel(context.Background())
		taildropCancel = cancel
		stateMu.Unlock()
		go startTaildropCollector(ctx, opt.TaildropDir)
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
		if err := startDNSProxy(ctx, opt.DnsProxy, fallbacks, doh); err != nil {
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
	if taildropCancel != nil {
		taildropCancel()
		taildropCancel = nil
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

func killLeftoverDaemons(daemonPath string) {
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
		Socket:        pc.Socket(),
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
