package appctr

import (
	"context"
	"encoding/json"
	"errors"
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
	return dialableAddr(c.Socks5Addr), c.Socks5User, c.Socks5Pass, c.DNSAddr
}

// dialableAddr rewrites a listen address into one that can be dialed locally.
// A proxy bound to a wildcard address (0.0.0.0 / ::) so that the LAN can reach
// it is still reachable over loopback, which is what in-process consumers use.
func dialableAddr(addr string) string {
	if addr == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	switch host {
	case "", "0.0.0.0":
		return net.JoinHostPort("127.0.0.1", port)
	case "::":
		return net.JoinHostPort("::1", port)
	}
	return addr
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
var webUI *webUIState
var coreVersion string = "unknown"
var daemonStartTime time.Time

// daemonGeneration identifies the current daemon run so a supervisor goroutine
// left over from a previous run cannot tear down its successor.
var daemonGeneration uint64

// daemonCtx is the lifetime of the current run: Start()/AttachExternal() create
// it and every teardown cancels it. All post-start work (readiness wait,
// registration, settings sync) is bound to it, so a goroutine that was parked
// in waitForLocalAPI when the daemon was stopped cannot wake up on the NEXT
// daemon's socket and configure it with the previous run's options. Both are
// guarded by stateMu.
var daemonCtx context.Context
var daemonCancel context.CancelFunc

// daemonRunKey carries the generation inside daemonCtx so work spawned for a
// run can tell which run it belongs to without a second parameter.
type daemonRunKey struct{}

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
	LoginServer   string
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

var externalSocketPath string

func SetExternalSocketPath(path string) {
	stateMu.Lock()
	externalSocketPath = path
	PC.SetSocket(path)
	stateMu.Unlock()

	if path == "" {
		slog.Info("External daemon socket path cleared")
		StopIPNBusListener()
		return
	}
	slog.Info("External daemon socket path set", "path", path)
	EnsureIPNBusListener()
}

// IsRunning reports whether a daemon we can talk to is available.
//
// For an externally managed (root) daemon the socket is probed for real: a
// leftover socket file from a killed daemon must not be reported as running,
// otherwise every LocalAPI caller keeps hammering a dead socket.
func IsRunning() bool {
	stateMu.Lock()
	ext := externalSocketPath
	embedded := cmd != nil && cmd.Process != nil
	stateMu.Unlock()

	if ext != "" {
		return socketAlive(ext)
	}
	return embedded
}

// getPC snapshots the path set under stateMu. Start/AttachExternal/release
// rewrite it concurrently with the goroutines that need it, and pathControl is
// five strings, so an unlocked read can tear.
func getPC() pathControl {
	stateMu.Lock()
	defer stateMu.Unlock()
	return PC
}

// isCurrentGeneration reports whether gen still names the active daemon run.
func isCurrentGeneration(gen uint64) bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return gen == daemonGeneration
}

// newDaemonRunLocked bumps the generation, retires the previous run's context
// and installs a fresh one. Caller holds stateMu.
func newDaemonRunLocked() context.Context {
	daemonGeneration++
	if daemonCancel != nil {
		daemonCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	daemonCtx = context.WithValue(ctx, daemonRunKey{}, daemonGeneration)
	daemonCancel = cancel
	return daemonCtx
}

// cancelDaemonRunLocked ends the current run's context. Caller holds stateMu.
func cancelDaemonRunLocked() {
	if daemonCancel != nil {
		daemonCancel()
		daemonCancel = nil
		daemonCtx = nil
	}
}

// currentDaemonCtx returns the context of the active run for work triggered
// outside Start/AttachExternal (ApplySettings, ReUp). Without an active run —
// only reachable through the exported SetExternalSocketPath — there is no
// lifetime to bind to, and the work runs unbounded as it always did.
func currentDaemonCtx() context.Context {
	stateMu.Lock()
	defer stateMu.Unlock()
	if daemonCtx == nil {
		return context.Background()
	}
	return daemonCtx
}

// runGeneration reports which daemon run a context was created for, 0 when it
// is not a run context.
func runGeneration(ctx context.Context) uint64 {
	if g, ok := ctx.Value(daemonRunKey{}).(uint64); ok {
		return g
	}
	return 0
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
	splitDNSCache.Range(func(key, value interface{}) bool {
		splitDNSCache.Delete(key)
		return true
	})
	nodesCache.Range(func(key, value interface{}) bool {
		nodesCache.Delete(key)
		return true
	})
	setMagicDNSSuffix("")
	slog.Info("DNS caches and metadata reset")
}

func ResetDNSMetadata() {
	FlushDNS()
}

// syncSettings pushes opt to the daemon once its LocalAPI answers. ctx is the
// run the options belong to: when it is cancelled (Stop, Detach, restart) the
// sync stops before it can PATCH the successor daemon with these options.
func syncSettings(ctx context.Context, opt *StartOptions) {
	if opt == nil {
		return
	}
	go func() {
		if !waitForLocalAPI(ctx, 15*time.Second) {
			if ctx.Err() == nil {
				slog.Warn("syncSettings: LocalAPI never became ready, settings not applied")
			}
			return
		}

		// Check backend status; skip PATCH /prefs if in NeedsLogin state to avoid resetting controlclient / interrupting interactive login
		statusDataStr, err := GetStatusJSON(false)
		if err == nil && len(statusDataStr) > 0 {
			var st struct {
				BackendState string `json:"BackendState"`
				AuthURL      string `json:"AuthURL"`
			}
			if json.Unmarshal([]byte(statusDataStr), &st) == nil {
				if st.BackendState == "NeedsLogin" || st.AuthURL != "" {
					slog.Info("syncSettings: backend is in NeedsLogin state, skipping PATCH /prefs to preserve login flow")
					return
				}
			}
		}

		prefs := make(map[string]interface{})
		if opt.Hostname != "" {
			prefs["Hostname"] = opt.Hostname
			prefs["HostnameSet"] = true
		}
		prefs["RouteAll"] = opt.AcceptRoutes
		prefs["RouteAllSet"] = true
		prefs["CorpDNS"] = opt.AcceptDNS
		prefs["CorpDNSSet"] = true
		controlURL := opt.LoginServer
		if controlURL == "" {
			controlURL = "https://controlplane.tailscale.com"
		}
		prefs["ControlURL"] = controlURL
		prefs["ControlURLSet"] = true

		// Push upstream resolvers to daemon so it can forward external DNS
		// queries even when no ExitNode is active (avoids "no upstream resolvers" SERVFAIL).
		var resolvers []map[string]interface{}
		if opt.DohFallback != "" && opt.DohFallback != "none" {
			resolvers = append(resolvers, map[string]interface{}{"Addr": opt.DohFallback})
		} else {
			resolvers = append(resolvers, map[string]interface{}{"Addr": "https://1.1.1.1/dns-query"})
		}

		if opt.DnsFallbacks != "" {
			for _, addr := range strings.Split(opt.DnsFallbacks, ",") {
				addr = strings.TrimSpace(addr)
				if addr != "" {
					if !strings.Contains(addr, ":") {
						addr = addr + ":53"
					}
					resolvers = append(resolvers, map[string]interface{}{"Addr": addr})
				}
			}
		} else {
			resolvers = append(resolvers, map[string]interface{}{"Addr": "8.8.8.8:53"})
			resolvers = append(resolvers, map[string]interface{}{"Addr": "1.1.1.1:53"})
		}

		if len(resolvers) > 0 {
			prefs["OverrideDNSResolvers"] = resolvers
			prefs["OverrideDNSResolversSet"] = true
		}

		prefs["ExitNodeID"] = opt.ExitNodeID
		prefs["ExitNodeIDSet"] = true
		prefs["ExitNodeIP"] = ""
		prefs["ExitNodeIPSet"] = true
		prefs["RunWebClient"] = opt.EnableWebUI
		prefs["RunWebClientSet"] = true
		prefs["WantRunning"] = true
		prefs["WantRunningSet"] = true

		// The status round-trip above may have outlived the run; do not hand
		// these prefs to whichever daemon owns the socket now.
		if ctx.Err() != nil {
			return
		}
		jsonData, _ := json.Marshal(prefs)
		slog.Info("Syncing settings via LocalAPI", "payload", string(jsonData))
		SetPrefs(string(jsonData))

		if ctx.Err() != nil {
			return
		}
		if opt.EnableWebUI {
			StartWebUI(opt.WebUIAddr)
		} else {
			StopWebUI()
		}
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
		syncSettings(currentDaemonCtx(), opt)
		return
	}

	if old.Socks5Server != opt.Socks5Server ||
		old.HttpProxy != opt.HttpProxy ||
		old.ControlProxy != opt.ControlProxy ||
		old.Socks5User != opt.Socks5User ||
		old.Socks5Pass != opt.Socks5Pass ||
		old.StatePath != opt.StatePath ||
		old.LoginServer != opt.LoginServer {
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

	syncSettings(currentDaemonCtx(), opt)
}

func ReUp() {
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if opt != nil && IsRunning() {
		if GetLoginURL() != "" {
			slog.Info("Login is in progress, skipping ReUp")
			return
		}
		if opt.AuthKey != "" {
			Login(opt.AuthKey)
		} else {
			go registerMachineWithAuthKey(currentDaemonCtx(), opt)
		}
	}
}

func Start(opt *StartOptions) {
	Stop()
	time.Sleep(1 * time.Second)

	// Default the SOCKS address before opt is published: lastOptions readers,
	// GConfig (DNS-over-SOCKS, DoH, Taildrive proxy) and the daemon command
	// line must all see the same address. Defaulting it after GConfig.update
	// left GConfig with "" for the whole run, silently disabling every
	// SOCKS-routed consumer, and mutating opt after it was stored under
	// stateMu raced with its readers.
	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	pc := PC
	lastOptions = opt
	daemonStartTime = time.Now()
	ctx := newDaemonRunLocked()
	generation := daemonGeneration
	stateMu.Unlock()

	slog.Info("========================================")
	slog.Info("=== TAILSOCKS GO CORE STARTING ===", "version", coreVersion, "do_reset", opt.DoReset, "has_authkey", opt.AuthKey != "")
	slog.Info("========================================")
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

	killLeftoverDaemons()

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	go func() {
		err := tailscaledCmd(pc, generation, opt.DnsFallbacks, opt.Socks5Server, opt.HttpProxy, opt.Socks5User, opt.Socks5Pass, opt.TaildropDir, opt.ControlProxy)
		if errors.Is(err, errLaunchSuperseded) {
			// A Stop() or another Start() retired this run before its process
			// existed; there is nothing to tear down and nothing to report.
			slog.Info("Daemon launch abandoned, the run was stopped before the process started", "generation", generation)
			return
		}
		if err != nil {
			slog.Error("tailscaled cmd crashed", "err", err)
		}

		// Only the supervisor of the daemon that is still current may tear
		// things down. A restart leaves the previous supervisor parked in
		// Wait(); when its daemon finally exits it used to call Stop(), which
		// by then pointed at the freshly started process — killing the daemon
		// the restart had just brought up and reporting the service as dead.
		if !isCurrentGeneration(generation) {
			slog.Info("Previous daemon exited after a restart, leaving the current one alone", "generation", generation)
			return
		}

		Stop()
		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	go runPostStart(ctx, opt, "Daemon socket never became ready after start")

	if opt.DnsProxy != "" {
		RestartDNS()
	}

	startTaildropCollectorFor(opt.TaildropDir)
}

func AttachExternal(opt *StartOptions) {
	// A re-attach without an intervening DetachExternal (the root daemon died
	// and a START intent relaunched it) must retire the previous run — its
	// Taildrop collector, DNS proxy, bus listener and post-start goroutines —
	// instead of running them alongside the new ones. On a first attach every
	// step is a no-op, so this mirrors Start()'s Stop() prologue.
	releaseGoResources()

	stateMu.Lock()
	externalSocketPath = opt.SocketPath
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	lastOptions = opt
	daemonStartTime = time.Now()
	ctx := newDaemonRunLocked()
	stateMu.Unlock()

	slog.Info("========================================")
	slog.Info("=== TAILSOCKS GO CORE ATTACHING (ROOT) ===", "version", coreVersion, "do_reset", opt.DoReset, "has_authkey", opt.AuthKey != "")
	slog.Info("========================================")
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)

	go runPostStart(ctx, opt, "Root daemon socket never became ready, aborting attach")

	if opt.DnsProxy != "" {
		RestartDNS()
	}

	startTaildropCollectorFor(opt.TaildropDir)
}

// runPostStart waits for the daemon's LocalAPI and then performs the one-time
// setup of a run: bus listener, authentication and settings sync. Every step
// re-checks ctx: the readiness wait can outlive the run it was started for,
// and waking up on the successor daemon's socket must not apply this run's
// options to it.
func runPostStart(ctx context.Context, opt *StartOptions, notReadyMsg string) {
	if !waitForLocalAPI(ctx, 20*time.Second) {
		if ctx.Err() == nil {
			slog.Error(notReadyMsg)
		}
		return
	}
	if ctx.Err() != nil {
		return
	}
	EnsureIPNBusListener()
	if ctx.Err() != nil {
		return
	}
	if opt.AuthKey != "" {
		Login(opt.AuthKey)
	} else {
		registerMachineWithAuthKey(ctx, opt)
	}
	if ctx.Err() != nil {
		return
	}
	syncSettings(ctx, opt)
}

// startTaildropCollectorFor replaces the Taildrop collector for dir (no-op for
// an empty dir). Cancel-before-overwrite: a collector whose cancel was simply
// overwritten kept polling the waiting-files endpoint every 5s for the life of
// the process, with nothing left holding its cancel.
func startTaildropCollectorFor(dir string) {
	if dir == "" {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	stateMu.Lock()
	if taildropCancel != nil {
		taildropCancel()
	}
	taildropCancel = cancel
	stateMu.Unlock()
	go startTaildropCollector(ctx, dir)
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

	// Register the cancel synchronously, before the goroutine sleeps. The old
	// code slept first, so two quick RestartDNS calls left two listeners
	// fighting for the port, and a Stop() during the sleep window could not
	// cancel a proxy that had not registered yet — it then started after Stop
	// with no owner.
	ctx, cancel := context.WithCancel(context.Background())
	stateMu.Lock()
	dnsProxyCancel = cancel
	stateMu.Unlock()

	go func() {
		select {
		case <-ctx.Done():
			return
		case <-time.After(500 * time.Millisecond):
		}

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
	releaseGoResources()

	stateMu.Lock()
	defer stateMu.Unlock()
	// Intentional stop: retire the supervisor parked in Wait() for this
	// process. Otherwise it treats the exit as a crash and reports it through
	// CloseCallBack, which tears the service down in the middle of a restart
	// (RESTART_ACTION, auto-reconnect) and flips desired_running to false.
	// The bump happens even when cmd is still nil: a launch that has not
	// registered its process yet checks the generation before starting it, so
	// a Stop() in that window abandons the launch instead of orphaning a
	// daemon the app believes is already stopped. Start() bumps the generation
	// again for the run it launches.
	daemonGeneration++
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		go func(p *os.Process) {
			time.Sleep(2 * time.Second)
			_ = p.Kill()
		}(cmd.Process)
		cmd = nil
	}
}

// DetachExternal releases every Go-side resource bound to an externally managed
// (root) daemon without terminating the daemon process itself. Used when the
// service stops but the user asked the root daemon to keep running.
func DetachExternal() {
	releaseGoResources()
	slog.Info("Detached from external daemon")
}

// releaseGoResources tears down everything the bridge owns — bus listener, DNS
// proxy, Taildrop collector, Taildrive server and proxy, Web UI, the run
// context of the post-start goroutines — and clears the socket path so no
// further LocalAPI request is attempted.
func releaseGoResources() {
	StopWebUI()
	StopDriveServer()
	// The Taildrive reverse proxy dials the SOCKS port of the daemon being
	// released. Kotlin stops it on its own shutdown path only, not when the
	// system destroys the service or the supervisor reports a crash, so the
	// bridge has to be self-sufficient here.
	_ = StopDriveProxy()
	StopIPNBusListener()
	FlushDNS()
	// After the listener is gone, so a late notification from the old stream
	// cannot repopulate the snapshot with the previous daemon's state. Without
	// the reset GetBackendState/GetLoginURL kept answering from the old run
	// ("Running" for a daemon still in NoState, a dead AuthURL that made
	// SetPrefs a silent no-op) until the new bus stream happened to overwrite it.
	resetBusState()
	// Drop the pooled LocalAPI connection so nothing is left holding the
	// daemon's socket after the bridge has let go of it, and the DoH pool that
	// tunnels through its SOCKS port.
	closeLocalClient()
	closeDoHClient()

	stateMu.Lock()
	defer stateMu.Unlock()
	externalSocketPath = ""
	daemonStartTime = time.Time{}
	PC.SetSocket("")

	cancelDaemonRunLocked()
	if dnsProxyCancel != nil {
		dnsProxyCancel()
		dnsProxyCancel = nil
	}
	if taildropCancel != nil {
		taildropCancel()
		taildropCancel = nil
	}
}

// killLeftoverDaemons removes any tailscaled a previous process left behind.
// Only the userspace (embedded) mode reaches this, where killing a stray
// daemon is the intent.
func killLeftoverDaemons() {
	_ = exec.Command("/system/bin/killall", "tailscaled").Run()
}

// webUIState is everything StartWebUI creates, kept together so StopWebUI can
// release all of it: the listener, the web.Server and the LocalAPI transport
// its client uses.
type webUIState struct {
	srv *http.Server
	ws  *web.Server
	tr  *http.Transport
}

func StartWebUI(addr string) {
	stateMu.Lock()
	if webUI != nil {
		if webUI.srv.Addr == addr {
			stateMu.Unlock()
			return
		}
		// Address changed, need to restart
		stateMu.Unlock()
		StopWebUI()
		stateMu.Lock()
	}
	pc := PC
	stateMu.Unlock()

	slog.Info("Web UI start requested", "addr", addr)
	// Own the LocalAPI transport instead of letting local.Client build one
	// lazily: its default has no IdleConnTimeout and is unreachable from
	// StopWebUI, so every WebUI restart (EnableWebUI toggled, address changed)
	// left connections to the daemon socket idle forever.
	sock := pc.Socket()
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", sock)
		},
		MaxIdleConns:        2,
		MaxIdleConnsPerHost: 2,
		IdleConnTimeout:     60 * time.Second,
	}
	lc := &local.Client{
		Socket:        sock,
		UseSocketOnly: true,
		Transport:     tr,
	}
	ws, err := web.NewServer(web.ServerOpts{
		Mode:        web.LoginServerMode,
		LocalClient: lc,
		Logf: func(format string, args ...any) {
			slog.Info("web", "msg", fmt.Sprintf(format, args...))
		},
	})
	if err != nil {
		tr.CloseIdleConnections()
		slog.Error("Failed to create web server", "err", err)
		return
	}
	srv := &http.Server{
		Addr:    addr,
		Handler: ws,
	}
	st := &webUIState{srv: srv, ws: ws, tr: tr}
	stateMu.Lock()
	webUI = st
	stateMu.Unlock()
	go func() {
		slog.Info("Web UI listening", "addr", addr)
		// Use the captured local, not the global: StopWebUI can clear the global
		// concurrently, which would nil-deref here and crash the whole process.
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Web UI listen error", "err", err)
		}
		stateMu.Lock()
		mine := webUI == st
		if mine {
			webUI = nil
		}
		stateMu.Unlock()
		if mine {
			// Listen failed without a StopWebUI to clean up after us.
			st.tr.CloseIdleConnections()
		}
	}()
}

func StopWebUI() {
	stateMu.Lock()
	st := webUI
	webUI = nil
	stateMu.Unlock()
	if st != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := st.srv.Shutdown(ctx); err != nil {
			// A handler parked in the web server's IPN-bus long-poll outlives
			// the graceful window; force it, or its streaming LocalAPI
			// connection survives the stop.
			_ = st.srv.Close()
		}
		st.ws.Shutdown()
		st.tr.CloseIdleConnections()
		slog.Info("Web UI stopped")
	}
}

func GetDaemonStartTime() int64 {
	stateMu.Lock()
	defer stateMu.Unlock()
	return daemonStartTime.Unix()
}
