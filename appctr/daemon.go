package appctr

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// cmd and stateMu are declared in appctr.go; do not redeclare here.

// errLaunchSuperseded is returned by tailscaledCmd when a Stop() or another
// Start() retired the run before its process was started.
var errLaunchSuperseded = errors.New("daemon launch superseded before the process started")

// tailscaledCmd launches the daemon for the run identified by generation and
// blocks until it exits. Between Start() spawning this and cmd being assigned
// there is filesystem work (rm/ln) during which a Stop() used to find cmd == nil
// and do nothing; the process then started anyway, orphaned, with a supervisor
// that later reported it as a crash. The generation check closes that window.
// hostinfoReport is what the daemon is told to say about this device in its
// Hostinfo. With honest false the patch-06 masquerade applies (OS "linux",
// App "tailscale-cli", DeviceModel "Tailsocks"); with honest true the daemon
// keeps OS "android" and reports the values below, read by the hook in
// cmd/tailscaled/fix_android_netmon.go from TS_HONEST_HOSTINFO / TS_HI_*.
type hostinfoReport struct {
	honest      bool
	osVersion   string
	deviceModel string
	pkg         string
}

// env returns the variables the daemon reads for the report; nil when the
// masquerade is wanted, so an unset variable means "as before".
func (h hostinfoReport) env() []string {
	if !h.honest {
		return nil
	}
	return []string{
		"TS_HONEST_HOSTINFO=1",
		"TS_HI_OS_VERSION=" + h.osVersion,
		"TS_HI_DEVICE_MODEL=" + h.deviceModel,
		"TS_HI_PACKAGE=" + h.pkg,
	}
}

// linkBinaries refreshes the `tailscale` and `tailscaled` names in the data
// directory, which point into the install's native library directory. Android
// renames that directory on every (re)install, so a link made by an earlier
// launch dangles. Both launch paths call this: the Root Mode attach never did,
// so after a reinstall the Console's `status`, `netcheck` and `ping` had no
// binary to run until the daemon was once started in userspace.
//
// Both names lead to the same binary. The name is what picks the role: the
// daemon built with ts_include_cli becomes the CLI when argv[0] is "tailscale"
// (cmd/tailscaled shouldRunCLI).
func linkBinaries(p pathControl) {
	rm(p.Tailscale(), p.Tailscaled())
	ln(p.TailscaledSo(), p.Tailscale())
	ln(p.TailscaledSo(), p.Tailscaled())
}

// cliArgv0 is the name under which the combined binary acts as the CLI.
const cliArgv0 = "tailscale"

// cliCommand runs the CLI with args. The data-directory link is preferred, so
// the process is named `tailscale` in ps as well; if it dangles (the ln
// shell-out failed) the binary is run directly, still with argv[0]
// "tailscale" — without that name it would start a second daemon.
func cliCommand(p pathControl, args ...string) *exec.Cmd {
	path := p.Tailscale()
	if _, err := os.Stat(path); err != nil {
		path = p.TailscaledSo()
	}
	c := exec.Command(path, args...)
	c.Args[0] = cliArgv0
	return c
}

// netmapCacheEnv is the daemon's environment for the netmap cache: nothing
// when it is on, so upstream's default applies, and TS_USE_CACHED_NETMAP=false
// when the user turned it off.
//
// Since 1.104 tailscaled keeps the last network map on disk, in
// <statedir>/profile-data/<profile>/netmap-cache, and starts from it before
// the coordination server answers (ipn/ipnlocal/diskcache.go) — but only while
// control grants the node the "cache-network-maps" attribute; without it the
// cache is neither written nor read, and an existing one is discarded. The
// envknob (default true) is upstream's local override: false is treated like
// the attribute being withdrawn, so the next network map deletes the cache.
// Upstream's own Android app builds with ts_omit_cachenetmap "until we have
// UI affordances for debugging it"; this switch is that affordance here.
func netmapCacheEnv(off bool) []string {
	if !off {
		return nil
	}
	return []string{"TS_USE_CACHED_NETMAP=false"}
}

func tailscaledCmd(p pathControl, generation uint64, dnsFallbacks string, socksAddr, httpAddr, socksUser, socksPass, taildropDir, controlProxy string, hostinfo hostinfoReport, extraEnv []string) error {
	// Cheap early exit before the filesystem work; the authoritative check is
	// the one under stateMu below.
	if !isCurrentGeneration(generation) {
		return errLaunchSuperseded
	}

	linkBinaries(p)

	// Native TUN: the VpnService device travels as fd 3 (see nativetun.go).
	tunFile := nativeTunFile()
	tunArg := "--tun=userspace-networking"
	if tunFile != nil {
		tunArg = "--tun=android-vpn"
	}

	args := []string{
		tunArg,
		"--socks5-server=" + socksAddr,
		fmt.Sprintf("--statedir=%s", p.State()),
		fmt.Sprintf("--socket=%s", p.Socket()),
	}

	if httpAddr != "" {
		args = append(args, "--outbound-http-proxy-listen="+httpAddr)
	}

	c := exec.Command(p.Tailscaled(), args...)
	c.Dir = p.DataDir()

	// The interface list is a picture taken now; the daemon keeps its own live
	// view once it runs. The default route, which it cannot see at all, comes
	// through a file it re-reads whenever the app wakes it.
	routePath := p.DataDir() + "/netroute"
	stateMu.Lock()
	netState := latestInterfaceState
	defaultRoutePath = routePath
	route := defaultRouteInterface
	tz := timeZoneID
	stateMu.Unlock()
	if route != "" {
		if err := os.WriteFile(routePath, []byte(route), 0o600); err != nil {
			slog.Debug("Could not seed the default route interface", "err", err)
		}
	}

	c.Env = append(os.Environ(),
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
		"TS_NO_LOGS_NO_SUPPORT=true",
		"TS_AUTH_ONCE=true",
		"TS_NET_STATE="+netState,
		"TS_NETROUTE_FILE="+routePath,
		// The control connection goes straight to TLS on port 443. By default
		// tailscaled upgrades a plain port 80 connection first and stays on
		// it once the handshake passes; some networks let that handshake
		// through and then drop the stream after a kilobyte or two, so the
		// node registers, never receives a network map, and hangs in
		// "connecting". Port 80 is still dialled for a control server that
		// has no HTTPS port.
		"TS_FORCE_NOISE_443=true",
	)
	if tz != "" {
		c.Env = append(c.Env, "TZ="+tz)
	}
	if dnsFallbacks != "" {
		c.Env = append(c.Env, "TS_DNS_FALLBACK="+dnsFallbacks)
	} else {
		c.Env = append(c.Env, "TS_DNS_FALLBACK=1.1.1.1,8.8.8.8")
	}

	// Proxy configuration (Outbound)
	// We clear TS_SOCKS5_SERVER here to ensure it's only set via flags if needed
	c.Env = append(c.Env, "TS_SOCKS5_SERVER=")

	if controlProxy != "" {
		// Fail closed. tailscaled reads these variables through
		// proxy.FromEnvironment, which silently falls back to a DIRECT
		// connection whenever the URL does not parse — a stray "/" or "?" in
		// a password used to switch the proxy off without a word. A proxy the
		// user configured but that cannot be honoured must stop the start.
		if err := validateProxyURL(controlProxy); err != nil {
			slog.Error("Proxy: control proxy URL is unusable, refusing to start the daemon without it", "err", err)
			return fmt.Errorf("control proxy URL is unusable: %w", err)
		}
		if staticOverride := resolveProxyHostStatic(controlProxy); staticOverride != "" {
			c.Env = append(c.Env, "TS_STATIC_HOSTS="+staticOverride)
			slog.Info("Proxy: Set static DNS override for proxy host", "override", staticOverride)
		}
		if strings.HasPrefix(controlProxy, "socks5://") {
			// For SOCKS5: use ALL_PROXY only. Do NOT add HTTP_PROXY or HTTPS_PROXY.
			c.Env = append(c.Env, "ALL_PROXY="+controlProxy)
			slog.Info("Proxy: Using SOCKS5 via ALL_PROXY", "url", redactProxyURL(controlProxy))
		} else {
			// For HTTP(S) proxy use the standard environment variables.
			c.Env = append(c.Env,
				"HTTP_PROXY="+controlProxy,
				"HTTPS_PROXY="+controlProxy,
			)
			slog.Info("Proxy: Using HTTP via HTTP_PROXY", "url", redactProxyURL(controlProxy))
		}
	}
	if taildropDir != "" {
		c.Env = append(c.Env, "TS_TAILDROP_DIR="+taildropDir)
	}
	// Both or neither, everywhere: the served proxy, the TUN engine's client,
	// the bridge's own DNS and Taildrive clients and Root mode all apply this
	// one rule. They used to disagree — some needed one field, some both — so
	// a username without a password made the server demand credentials that
	// the TUN engine and Taildrive never sent.
	if socksUser != "" && socksPass != "" {
		c.Env = append(c.Env, "TS_SOCKS5_USER="+socksUser)
		c.Env = append(c.Env, "TS_SOCKS5_PASS="+socksPass)
	}
	c.Env = append(c.Env, hostinfo.env()...)
	c.Env = append(c.Env, extraEnv...)
	if tunFile != nil {
		c.ExtraFiles = []*os.File{tunFile} // fd 3 in the child
		c.Env = append(c.Env, "TS_TUN_FD=3")
		slog.Info("Daemon launch: native TUN, the VpnService device is attached")
	}

	// Check, start and register under one lock so Stop() can never observe a
	// launch it is unable to retire: before this section the generation check
	// abandons the launch, after it cmd is set and Stop() signals the process.
	// The fork/exec takes a few milliseconds and happens once per run.
	stateMu.Lock()
	if generation != daemonGeneration {
		stateMu.Unlock()
		return errLaunchSuperseded
	}
	stdOut, err := c.StdoutPipe()
	if err != nil {
		stateMu.Unlock()
		return err
	}
	stdErr, err := c.StderrPipe()
	if err != nil {
		stateMu.Unlock()
		return err
	}
	if err := c.Start(); err != nil {
		stateMu.Unlock()
		return err
	}
	cmd = c
	stateMu.Unlock()

	// A daemon log line can exceed bufio.Scanner's default 64 KB token, which
	// silently kills the scanner and drops all later output; give it room.
	scan := func(r interface{ Read([]byte) (int, error) }) {
		s := bufio.NewScanner(r)
		s.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); scan(stdOut) }()
	go func() { defer wg.Done(); scan(stdErr) }()

	// Drain both pipes before Wait() closes them, so the last lines before a
	// crash — the interesting ones — are not lost.
	wg.Wait()
	return c.Wait()
}

// validateProxyURL checks that a control-proxy URL will actually be honoured
// by tailscaled's proxy.FromEnvironment: a known scheme, a host, and a port.
func validateProxyURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return err
	}
	switch strings.ToLower(u.Scheme) {
	case "socks5", "socks5h", "http", "https":
	default:
		return fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	if u.Hostname() == "" {
		return errors.New("missing host")
	}
	if u.Port() == "" {
		return errors.New("missing port")
	}
	if u.Path != "" && u.Path != "/" || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("path, query or fragment in proxy URL (unescaped credential characters?)")
	}
	return nil
}

// redactProxyURL hides the credentials of a proxy URL for logging; the log
// buffer is user-visible, copyable and exportable.
func redactProxyURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "<unparseable proxy url>"
	}
	if u.User != nil {
		if _, hasPass := u.User.Password(); hasPass {
			u.User = url.UserPassword(u.User.Username(), "***")
		}
	}
	return u.String()
}

func resolveProxyHostStatic(rawURL string) string {
	if rawURL == "" {
		return ""
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	host := u.Hostname()
	if host == "" || net.ParseIP(host) != nil {
		return ""
	}

	// 1. Try standard Go resolver
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	addrs, err := net.DefaultResolver.LookupHost(ctx, host)
	if err == nil && len(addrs) > 0 {
		return host + "=" + addrs[0]
	}

	// 2. Direct DNS query fallback via 1.1.1.1:53 UDP
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 2 * time.Second}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}
	addrs, err = r.LookupHost(ctx, host)
	if err == nil && len(addrs) > 0 {
		return host + "=" + addrs[0]
	}

	return ""
}
