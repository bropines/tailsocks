package appctr

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"strings"
	"os/exec"
	"sync"
	"time"
	"net/http"
	"path/filepath"
	"encoding/json"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/client/local"
	"tailscale.com/client/web"
	"tailscale.com/net/netcheck"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
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

func doLocalRequest(method, path string, body io.Reader) ([]byte, error) {
	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	if pc.Socket() == "" {
		return nil, fmt.Errorf("socket path is empty")
	}

	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
	}

	req, err := http.NewRequest(method, "http://local-tailscaled.sock"+path, body)
	if err != nil {
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(data))
	}

	return data, nil
}

func GetStatusFromAPI() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}
	// Важно: peers=true заставляет API вернуть список всех нод
	data, err := doLocalRequest("GET", "/localapi/v0/status?peers=true", nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}
	if len(data) == 0 {
		return `{"Error": "Status API returned empty response"}`
	}
	return string(data)
}

func GetDnsStatusJSON() string {
	if !IsRunning() {
		return "{}"
	}

	socks, _, _, dns := GConfig.get()
	
	// Собираем структуру, совместимую с DnsActivity.kt
	type dnsAddr struct {
		Addr string `json:"Addr"`
	}
	type tailnetInfo struct {
		MagicDNSEnabled bool    `json:"MagicDNSEnabled"`
		MagicDNSSuffix  string  `json:"MagicDNSSuffix"`
		SelfDNSName     string  `json:"SelfDNSName"`
	}
	type status struct {
		TailscaleDNS   bool                    `json:"TailscaleDNS"`
		CurrentTailnet tailnetInfo             `json:"CurrentTailnet"`
		SplitDNSRoutes map[string][]dnsAddr   `json:"SplitDNSRoutes"`
	}

	res := status{
		TailscaleDNS: dns != "",
		CurrentTailnet: tailnetInfo{
			MagicDNSEnabled: magicDNSSuffix != "",
			MagicDNSSuffix:  magicDNSSuffix,
		},
		SplitDNSRoutes: make(map[string][]dnsAddr),
	}

	// Заполняем маршруты из нашего кэша
	splitDNSCache.Range(func(key, value interface{}) bool {
		domain := key.(string)
		ips := value.([]string)
		var addrs []dnsAddr
		for _, ip := range ips {
			addrs = append(addrs, dnsAddr{Addr: ip})
		}
		res.SplitDNSRoutes[domain] = addrs
		return true
	})

	// Пытаемся найти свое имя
	if socks != "" { // Просто как индикатор что мы инициализированы
		nodesCache.Range(func(key, value interface{}) bool {
			name := key.(string)
			if strings.HasSuffix(name, magicDNSSuffix) && !strings.Contains(strings.TrimSuffix(name, magicDNSSuffix), ".") {
				res.CurrentTailnet.SelfDNSName = name
				return false
			}
			return true
		})
	}

	data, _ := json.Marshal(res)
	return string(data)
}

func NativeDnsQuery(domain, qtype string) string {
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
	if strings.ToUpper(qtype) == "AAAA" { t = dnsmessage.TypeAAAA }

	name, err := dnsmessage.NewName(domain + ".")
	if err != nil { return "Invalid domain" }

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
	if resp == nil { return "No response" }

	var respMsg dnsmessage.Message
	if err := respMsg.Unpack(resp); err != nil { return "Error unpacking: " + err.Error() }

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

func GetNetcheckFromAPI() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}

	// 1. Получаем DERP map из демона
	data, err := doLocalRequest("GET", "/localapi/v0/derpmap", nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Failed to get DERP map: %v"}`, err)
	}

	var dm tailcfg.DERPMap
	if err := json.Unmarshal(data, &dm); err != nil {
		return fmt.Sprintf(`{"Error": "Failed to parse DERP map: %v"}`, err)
	}

	// 2. Инициализируем монитор сети (статичный, без шины событий)
	nm := netmon.NewStatic()
	defer nm.Close()

	// 3. Запускаем нативный netcheck
	c := &netcheck.Client{
		NetMon: nm,
		Logf: func(format string, args ...any) {
			slog.Info(fmt.Sprintf("netcheck: "+format, args...))
		},
	}

	report, err := c.GetReport(context.Background(), &dm, nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Netcheck failed: %v"}`, err)
	}

	if report == nil {
		return `{"Error": "Netcheck returned nil report"}`
	}

	// 4. Возвращаем JSON отчета
	res, err := json.Marshal(report)
	if err != nil {
		return fmt.Sprintf(`{"Error": "JSON marshal failed: %v"}`, err)
	}
	return string(res)
}

func GetTaildropFilesFromAPI() string {
	if !IsRunning() {
		return "[]"
	}
	data, err := doLocalRequest("GET", "/localapi/v0/files/", nil)
	if err != nil {
		return "[]"
	}
	return string(data)
}

func DeleteTaildropFileFromAPI(name string) bool {
	if !IsRunning() {
		return false
	}
	_, err := doLocalRequest("DELETE", "/localapi/v0/files/"+name, nil)
	return err == nil
}

func SendFileFromAPI(peerID, filePath string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	
	f, err := os.Open(filePath)
	if err != nil {
		return "Error: " + err.Error()
	}
	defer f.Close()

	name := filepath.Base(filePath)
	// PUT /localapi/v0/file-put/<id>/<name>
	data, err := doLocalRequest("PUT", "/localapi/v0/file-put/"+peerID+"/"+name, f)
	if err != nil {
		return "Error: " + err.Error()
	}
	return string(data)
}

func GetBackendState() string {
	if !IsRunning() {
		return "Stopped"
	}
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err != nil {
		return "Error"
	}
	// Simple string parsing to avoid full JSON unmarshal for just one field
	s := string(data)
	idx := strings.Index(s, "\"BackendState\":\"")
	if idx == -1 {
		return "Unknown"
	}
	start := idx + len("\"BackendState\":\"")
	end := strings.Index(s[start:], "\"")
	if end == -1 {
		return "Unknown"
	}
	return s[start : start+end]
}

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
		old.ControlProxy != opt.ControlProxy ||
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
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

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