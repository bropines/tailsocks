package appctr

import (
	"context"
	"log/slog"
	"os/exec"
	"strings"
	"sync/atomic"
	"time"
)

var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc
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
	// What the daemon reports to the coordination server about this device.
	// false keeps the long-standing masquerade (OS "linux", DeviceModel
	// "Tailsocks"); true reports the real OS plus the model, Android version and
	// install source below. Either way App is "tailsocks", which is how peers
	// tell TailSocks apart. See cmd/tailscaled/fix_android_netmon.go.
	HonestHostinfo bool
	OsVersion      string
	DeviceModel    string
	InstallSource  string
	// NetmapCacheOff stops the daemon from starting on the network map it saved
	// last time (TS_USE_CACHED_NETMAP=false, see netmapCacheEnv). The zero value
	// keeps upstream's default, under which the coordination server decides.
	NetmapCacheOff bool
	// DnsRescueOff stops the daemon's resolver from retrying a failed query
	// against the fallback resolvers (TS_DNS_RESCUE=0, see dnsRescueEnv). The
	// zero value keeps the rescue on.
	DnsRescueOff bool
	// ControlPort80 lets the control connection try the plain port 80 upgrade
	// first, as upstream does. The zero value sends it straight to TLS on port
	// 443 (TS_FORCE_NOISE_443, see controlPortEnv).
	ControlPort80 bool
	// ClientPrefs carries the node switches Settings owns beyond the fields
	// above — shields up, LAN access through an exit node, posture reporting,
	// the peer relay, the app connector — as a JSON object of ipn.MaskedPrefs
	// fields with their …Set flags. Only switches the user has set are in it,
	// so one set from the CLI is left alone until touched. syncSettings PATCHes
	// it with the keys above, before ExtraUpArgs, so a typed flag still wins.
	ClientPrefs string
	// RemoteManagement lets paired devices manage this one over the tailnet
	// (remote.go). The zero value keeps the endpoint closed.
	RemoteManagement bool
	// RemoteFullControl is the second switch of remote management: what shows
	// more of this device to a network or shows what is on it — its storage
	// by name, shares, routes, the scrubbed log — is done only while it is on,
	// and only for an owner (remote_level.go). The zero value is the basic
	// level.
	RemoteFullControl bool
	// RemoteNoGuests keeps devices that cannot be owners from pairing and
	// from using a pairing they have. The zero value lets them in as guests.
	RemoteNoGuests bool
	// RemoteNoElevation keeps a person at the device from giving a guest full
	// rights for a while, and ends those in force (remote_elevate.go). The
	// zero value lets them be given.
	RemoteNoElevation bool
	// RemoteFiles is the device's network access to files: its storage by
	// name, its shared folders, its log and its report are reachable from afar
	// only while it is on, whatever the level and the class (remote_files.go).
	// The zero value keeps them closed.
	RemoteFiles bool
}

// SetLogLevel sets how much is logged: 0 is the detailed log, debug lines and
// the daemon's chatter included; 1 and above is the normal one. Atomic, not
// under stateMu: the log handler reads it on every line (dualHandler.Enabled),
// and lines are written with stateMu held.
func SetLogLevel(level int32) {
	atomic.StoreInt32(&currentLogLevel, level)
}

func logWithFilter(text string) {
	lvl := atomic.LoadInt32(&currentLogLevel)

	if lvl >= 1 {
		lower := strings.ToLower(text)
		if strings.Contains(lower, "magicsock") ||
			strings.Contains(lower, "netcheck") ||
			strings.Contains(lower, "ratelimit") ||
			strings.Contains(lower, "udp proxy: received") ||
			// A client that closed before saying anything: a port probe (the
			// diagnostics card makes one), a browser tab closed mid-connect.
			// Nothing to act on, and "failed" made the log file it as an error.
			// Older cores said EOF; since 1.102 the greeting parser says it
			// "could not read packet header".
			strings.Contains(lower, "socks5: client connection failed: eof") ||
			strings.Contains(lower, "socks5: client connection failed: could not read packet header") ||
			strings.Contains(lower, "logtail") {
			return
		}
	}
	slog.Info(text, "src", "daemon")
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
// otherwise every LocalAPI caller keeps hammering a dead socket. The bus
// stream from that socket, while it is open, is that proof without a probe
// (socketAlive).
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

func GetDaemonStartTime() int64 {
	stateMu.Lock()
	defer stateMu.Unlock()
	return daemonStartTime.Unix()
}
